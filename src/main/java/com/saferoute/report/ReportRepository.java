package com.saferoute.report;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * A파트의 불편 신고 표(023_road_report.sql)에 쓰고 읽는다: road_report(신고), road_report_photo(사진).
 * 좌표는 다른 표와 같이 POINT(경도, 위도), SRID 0.
 */
@Repository
public class ReportRepository {

    private static final double M_PER_DEG = 111_320.0;

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 신고 한 줄을 넣고 새 번호를 돌려준다. status 는 표 기본값(open) */
    public long insert(NewReport r, Long edgeId) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO road_report (report_type, mode, memo, lon, lat, geom, accuracy_m, edge_id)"
                            + " VALUES (?, ?, ?, ?, ?, ST_SRID(POINT(?, ?), 0), ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, r.type());
            ps.setString(2, r.mode());
            ps.setString(3, r.memo());
            ps.setDouble(4, r.lon());
            ps.setDouble(5, r.lat());
            ps.setDouble(6, r.lon());
            ps.setDouble(7, r.lat());
            if (r.accuracyM() == null) {
                ps.setNull(8, Types.SMALLINT);
            } else {
                ps.setInt(8, r.accuracyM());
            }
            if (edgeId == null) {
                ps.setNull(9, Types.BIGINT);
            } else {
                ps.setLong(9, edgeId);
            }
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /** 사진 한 장 넣기 (seq 는 1부터) */
    public void insertPhoto(long reportId, int seq, NewReport.Photo p) {
        jdbc.update("INSERT INTO road_report_photo (report_id, seq, content_type, size_bytes, data) VALUES (?, ?, ?, ?, ?)",
                reportId, seq, p.contentType(), p.data().length, p.data());
    }

    /**
     * 신고 위치에서 maxM 안의 가장 가까운 길(road_edge.id), 없으면 null.
     * 나중에 확인된 신고를 경로에 반영할 때 B파트가 쓴다 (표 설명: 비어도 됨).
     */
    public Long nearestEdge(double lat, double lon, int maxM) {
        double dLat = maxM / M_PER_DEG;
        double dLon = maxM / (M_PER_DEG * Math.cos(Math.toRadians(lat)));
        String box = box(lon - dLon, lat - dLat, lon + dLon, lat + dLat);
        // SRID 0 이라 ST_Distance 는 '도' 단위지만, 몇십 m 안에서 가장 가까운 것을 고르는 데는 충분하다
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM road_edge WHERE MBRIntersects(ST_GeomFromText(?, 0), geom)"
                        + " ORDER BY ST_Distance(geom, ST_SRID(POINT(?, ?), 0)) LIMIT 1",
                Long.class, box, lon, lat);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 지도 범위 안 신고 (숨긴 것 빼고), 최근 순으로 limit 개까지 */
    public List<ReportItem> within(double west, double south, double east, double north, int limit) {
        List<ReportItem> out = new ArrayList<>();
        jdbc.query("SELECT r.id, r.report_type, r.mode, r.memo, r.lat, r.lon, r.status,"
                        + " DATE_FORMAT(r.created_at, '%Y-%m-%dT%H:%i') AS created,"
                        + " (SELECT COUNT(*) FROM road_report_photo p WHERE p.report_id = r.id) AS photos"
                        + " FROM road_report r WHERE r.status IN ('open', 'confirmed')"
                        + " AND MBRContains(ST_GeomFromText(?, 0), r.geom)"
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT ?",
                rs -> {
                    long id = rs.getLong("id");
                    String type = rs.getString("report_type");
                    List<String> urls = new ArrayList<>();
                    for (int i = 1; i <= rs.getInt("photos"); i++) {
                        urls.add("/api/reports/" + id + "/photos/" + i);
                    }
                    out.add(new ReportItem(id, type, ReportType.name(type), rs.getString("mode"), rs.getString("memo"),
                            rs.getDouble("lat"), rs.getDouble("lon"), rs.getString("status"), rs.getString("created"),
                            List.copyOf(urls)));
                }, box(west, south, east, north), limit);
        return out;
    }

    /** 사진 한 장. 신고가 없거나 숨긴 신고면 null */
    public NewReport.Photo photo(long reportId, int seq) {
        List<NewReport.Photo> found = jdbc.query(
                "SELECT p.content_type, p.data FROM road_report_photo p JOIN road_report r ON r.id = p.report_id"
                        + " WHERE p.report_id = ? AND p.seq = ? AND r.status <> 'hidden'",
                (rs, n) -> new NewReport.Photo(rs.getString("content_type"), rs.getBytes("data")), reportId, seq);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String box(double w, double s, double e, double n) {
        return String.format(Locale.ROOT, "POLYGON((%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f))",
                w, s, e, s, e, n, w, n, w, s);
    }
}
