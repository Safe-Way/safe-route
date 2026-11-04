package com.saferoute.facility;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A파트의 poi_status 뷰(정제 시설 + 지금 이용 가능 판단, 012_poi.sql)를 읽는다.
 * 지금 상태(now_status·now_note)는 DB 시각(학교 DB는 한국 시각)으로 뷰가 계산한다.
 * 범위 조건(MBRContains)은 뷰 안의 poi 표 공간 인덱스로 내려가 빠르게 돈다.
 */
@Repository
public class FacilityRepository {

    private static final double M_PER_DEG = 111_320.0;
    private static final String COLUMNS = "id, kind, sub, name, address, lat, lon, cnt, wheelchair, modes, detail,"
            + " now_status, now_note";

    private final JdbcTemplate jdbc;

    public FacilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 한 점에서 radiusM 안의 한 종류 시설을 가까운 순으로.
     *
     * @param wheelchairOnly true면 wheelchair = 1인 것만 (장애인 화장실 등)
     */
    public List<Facility> near(double lat, double lon, String kind, boolean wheelchairOnly, int radiusM) {
        double dLat = radiusM / M_PER_DEG;
        double dLon = radiusM / (M_PER_DEG * Math.cos(Math.toRadians(lat)));
        String box = polygon(lon - dLon, lat - dLat, lon + dLon, lat + dLat);
        String sql = "SELECT " + COLUMNS + ", ST_Distance_Sphere(geom, ST_SRID(POINT(?, ?), 0)) AS dist"
                + " FROM poi_status WHERE MBRContains(ST_GeomFromText(?, 0), geom) AND kind = ?"
                + (wheelchairOnly ? " AND wheelchair = 1" : "")
                + " HAVING dist <= ? ORDER BY dist LIMIT 200";
        return jdbc.query(sql, (rs, n) -> row(rs, (int) Math.round(rs.getDouble("dist"))),
                lon, lat, box, kind, radiusM);
    }

    /** 지도 범위 안의 여러 종류 시설. 가운데에 가까운 순으로 limit개까지 */
    public List<Facility> within(double west, double south, double east, double north, Collection<String> kinds,
            int limit) {
        if (kinds.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", Collections.nCopies(kinds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(polygon(west, south, east, north));
        args.addAll(kinds);
        args.add((west + east) / 2);
        args.add((south + north) / 2);
        args.add(limit);
        String sql = "SELECT " + COLUMNS + " FROM poi_status WHERE MBRContains(ST_GeomFromText(?, 0), geom)"
                + " AND kind IN (" + in + ")"
                + " ORDER BY ST_Distance_Sphere(geom, ST_SRID(POINT(?, ?), 0)) LIMIT ?";
        return jdbc.query(sql, (rs, n) -> row(rs, null), args.toArray());
    }

    private static Facility row(ResultSet rs, Integer distanceM) throws SQLException {
        Object wc = rs.getObject("wheelchair");
        String modes = rs.getString("modes");
        return new Facility(rs.getLong("id"), rs.getString("kind"), rs.getString("sub"), rs.getString("name"),
                rs.getString("address"), rs.getDouble("lat"), rs.getDouble("lon"), distanceM,
                rs.getString("now_status"), rs.getString("now_note"),
                wc == null ? null : ((Number) wc).intValue() == 1, Math.max(1, rs.getInt("cnt")),
                modes == null || modes.isBlank() ? List.of() : Arrays.asList(modes.split(",")),
                rs.getString("detail"));
    }

    /** 경도·위도 사각형 (SRID 0, x = 경도) */
    static String polygon(double west, double south, double east, double north) {
        return String.format(Locale.ROOT, "POLYGON((%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f))",
                west, south, east, south, east, north, west, north, west, south);
    }
}
