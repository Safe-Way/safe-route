package com.saferoute.bus;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A파트의 bus_stop 표(광주 버스 정류장, 019_transit.sql)에서 가까운 정류장을 찾는다.
 * 네모 범위(MBRContains)로 먼저 줄여서 공간 인덱스를 타고, 그다음 실제 거리로 자른다.
 */
@Repository
public class BusStopRepository {

    private static final double M_PER_DEG = 111_320.0;

    private final JdbcTemplate jdbc;

    public BusStopRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 한 점에서 radiusM 안의 정류장을 가까운 순으로 limit개까지 */
    public List<BusStop> near(double lat, double lon, int radiusM, int limit) {
        double dLat = radiusM / M_PER_DEG;
        double dLon = radiusM / (M_PER_DEG * Math.cos(Math.toRadians(lat)));
        String box = String.format(Locale.ROOT, "POLYGON((%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f))",
                lon - dLon, lat - dLat, lon + dLon, lat - dLat, lon + dLon, lat + dLat, lon - dLon, lat + dLat,
                lon - dLon, lat - dLat);
        String sql = "SELECT busstop_id, name, next_busstop, lat, lon,"
                + " ST_Distance_Sphere(geom, ST_SRID(POINT(?, ?), 0)) AS dist"
                + " FROM bus_stop WHERE MBRContains(ST_GeomFromText(?, 0), geom)"
                + " HAVING dist <= ? ORDER BY dist LIMIT ?";
        return jdbc.query(sql, (rs, n) -> new BusStop(rs.getInt("busstop_id"), rs.getString("name"),
                        rs.getString("next_busstop"), rs.getDouble("lat"), rs.getDouble("lon"),
                        (int) Math.round(rs.getDouble("dist"))),
                lon, lat, box, radiusM, limit);
    }

    /** 정류장 번호들로 이름·좌표 찾기 (버스 위치용). 표에 없는 번호는 결과에 없다. 거리는 0 */
    public Map<Integer, BusStop> byIds(Collection<Integer> ids) {
        Map<Integer, BusStop> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT busstop_id, name, next_busstop, lat, lon FROM bus_stop WHERE busstop_id IN (" + marks + ")";
        jdbc.query(sql, rs -> {
            BusStop s = new BusStop(rs.getInt("busstop_id"), rs.getString("name"), rs.getString("next_busstop"),
                    rs.getDouble("lat"), rs.getDouble("lon"), 0);
            out.put(s.id(), s);
        }, ids.toArray());
        return out;
    }
}
