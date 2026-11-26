package com.saferoute.report;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
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

    private static final Logger log = LoggerFactory.getLogger(ReportRepository.class);
    private static final double M_PER_DEG = 111_320.0;

    private final JdbcTemplate jdbc;

    /** 033(reporter_hash 칸·reporter_count 뷰)이 DB에 있는지. 없다고 한 번 확인되면 서버를 다시 켤 때까지 예전 방식 */
    private volatile boolean hasReporterColumns = true;

    /** 036(photo_label 칸)이 DB에 있는지. 없다고 한 번 확인되면 서버를 다시 켤 때까지 예전처럼(차량도 하루) */
    private volatile boolean hasPhotoLabel = true;

    /** 차·오토바이로 판단된 '막는 물건' 신고는 몇 시간만 유효한지 — 036 뷰 road_report_active 와 같음 */
    static final int VEHICLE_HOURS = 3;

    /** 종류별 신고 기간(일) — 결정 57, 뷰 road_report_active·scripts/review_reports.py 와 같음 */
    private static String days(String t) {
        return "CASE " + t + ".report_type WHEN 'obstacle' THEN 1 WHEN 'accident' THEN 1 WHEN 'construction' THEN 7"
                + " WHEN 'curb' THEN 30 WHEN 'steep' THEN 30 WHEN 'broken' THEN 30 ELSE 7 END";
    }

    /**
     * 신고 r 둘레(같은 종류·30m 안, 자기 포함)에 '기간 끝 + keep일'이 아직 안 지난 신고가 있으면 참.
     * 숨긴 신고는 자기 자신만 지킨다. scripts/review_reports.py 의 still_kept 와 같은 SQL (결정 59).
     * keep = 0 이면 "아직 기간 안"이라는 뜻이라 지도 목록·사진에도 같은 조건을 쓴다.
     */
    static String stillKept(int keep) {
        return stillKept(keep, false);
    }

    /**
     * stillKept + 차량 3시간 규칙(036). vehicle3h 가 참이면 photo_label = 'vehicle' 인 '막는 물건' 신고는
     * 날짜 대신 올린 뒤 3시간까지만 유효하다고 본다. 지도 목록·사진(keep = 0)에만 쓴다 —
     * 사진·줄 지우기(keep 7·30)는 scripts/review_reports.py 와 글자 그대로 맞춰 두려고 바꾸지 않는다.
     */
    static String stillKept(int keep, boolean vehicle3h) {
        String byDate = "DATE_ADD(DATE(n.created_at), INTERVAL (" + days("n") + ") - 1 + " + keep + " DAY) >= CURDATE()";
        String period = !vehicle3h ? byDate
                : "(CASE WHEN n.report_type = 'obstacle' AND n.photo_label = 'vehicle'"
                + " THEN DATE_ADD(n.created_at, INTERVAL " + VEHICLE_HOURS + " HOUR) > NOW() ELSE " + byDate + " END)";
        return "EXISTS (SELECT 1 FROM road_report n WHERE (n.status <> 'hidden' OR n.id = r.id)"
                + " AND n.report_type = r.report_type"
                + " AND ST_Distance_Sphere(n.geom, r.geom) <= 30"
                + " AND " + period + ")";
    }

    /** 사진을 지울 신고: 숨긴 신고, 또는 둘레 신고까지 모두 '기간 끝 + 7일'이 지난 신고 (결정 59 PHOTO_DUE) */
    static final String PHOTO_DUE = "SELECT r.id FROM road_report r"
            + " WHERE EXISTS (SELECT 1 FROM road_report_photo p WHERE p.report_id = r.id)"
            + " AND (r.status = 'hidden' OR NOT " + stillKept(7) + ")";

    /** 줄을 지울 신고: 둘레 신고까지 모두 '기간 끝 + 30일'이 지난 신고 (결정 59 ROW_DUE) */
    static final String ROW_DUE = "SELECT r.id FROM road_report r WHERE NOT " + stillKept(30);

    /** 한 번에 지우는 개수 (IN 목록이 너무 길어지지 않게) */
    static final int DELETE_BATCH = 500;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 신고 한 줄을 넣고 새 번호를 돌려준다. status 는 표 기본값(open) */
    public long insert(NewReport r, Long edgeId) {
        return insert(r, edgeId, "photo");
    }

    /**
     * 신고 한 줄을 넣는다.
     *
     * @param via photo 화면에서 사진과 함께, voice 비서에게 말로 (028)
     */
    public long insert(NewReport r, Long edgeId, String via) {
        return insert(r, edgeId, via, null);
    }

    /**
     * 신고 한 줄을 넣는다.
     *
     * @param via          photo 화면에서 사진과 함께, voice 비서에게 말로 (028)
     * @param reporterHash 신고한 사람 표시 (ReporterHash, 033). 앱 번호가 없으면 null
     */
    public long insert(NewReport r, Long edgeId, String via, String reporterHash) {
        if (hasReporterColumns) {
            try {
                return insertRow(r, edgeId, via, reporterHash, true);
            } catch (BadSqlGrammarException e) {
                // 033(reporter_hash 칸)이 아직 DB에 안 들어갔으면 예전처럼 넣는다
                hasReporterColumns = false;
                log.warn("reporter_hash 칸이 없어 신고자 없이 저장해요 (033 반영 전): {}", e.getMostSpecificCause().getMessage());
            }
        }
        return insertRow(r, edgeId, via, null, false);
    }

    private long insertRow(NewReport r, Long edgeId, String via, String reporterHash, boolean withReporter) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO road_report (report_type, mode, memo, lon, lat, geom, accuracy_m, edge_id, via"
                            + (withReporter ? ", reporter_hash" : "")
                            + ") VALUES (?, ?, ?, ?, ?, ST_SRID(POINT(?, ?), 0), ?, ?, ?" + (withReporter ? ", ?" : "") + ")",
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
            ps.setString(10, via);
            if (withReporter) {
                ps.setString(11, reporterHash);
            }
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /**
     * 사진 종류 판단 결과 남기기 (036 photo_label). 칸이 아직 없으면(036 전) 남기지 않고 넘어간다.
     *
     * @param label 지금은 "vehicle" 만 (확신 없으면 부르지 않아 NULL 로 남음)
     */
    public void setPhotoLabel(long reportId, String label) {
        if (!hasPhotoLabel) {
            return;
        }
        try {
            jdbc.update("UPDATE road_report SET photo_label = ? WHERE id = ?", label, reportId);
        } catch (BadSqlGrammarException e) {
            hasPhotoLabel = false;
            log.warn("photo_label 칸이 없어 사진 종류를 안 남겨요 (036 반영 전): {}", e.getMostSpecificCause().getMessage());
        }
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
        if (hasPhotoLabel) {
            try {
                return withinRows(west, south, east, north, limit, true);
            } catch (BadSqlGrammarException e) {
                hasPhotoLabel = false;
                log.warn("photo_label 칸이 없어 차량 신고도 하루로 보여요 (036 반영 전): {}", e.getMostSpecificCause().getMessage());
            }
        }
        return withinRows(west, south, east, north, limit, false);
    }

    private List<ReportItem> withinRows(double west, double south, double east, double north, int limit,
                                        boolean vehicle3h) {
        List<ReportItem> out = new ArrayList<>();
        jdbc.query("SELECT r.id, r.report_type, r.mode, r.memo, r.lat, r.lon, r.status,"
                        + " DATE_FORMAT(r.created_at, '%Y-%m-%dT%H:%i') AS created,"
                        + " (SELECT COUNT(*) FROM road_report_photo p WHERE p.report_id = r.id) AS photos,"
                        + (vehicle3h ? " r.photo_label" : " NULL AS photo_label")
                        + " FROM road_report r WHERE r.status IN ('open', 'confirmed')"
                        + " AND MBRContains(ST_GeomFromText(?, 0), r.geom)"
                        + " AND " + stillKept(0, vehicle3h)
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
                            List.copyOf(urls), "obstacle".equals(type) && "vehicle".equals(rs.getString("photo_label"))));
                }, box(west, south, east, north), limit);
        return out;
    }

    /**
     * 지도 범위 안 지금 유효한 신고 지점 (road_report_active 뷰, 032). 마지막 신고가 최근인 순으로 limit 개까지.
     * 뷰가 기간·같은 곳 묶기를 계산하니 여기서는 범위만 거른다.
     */
    public List<ActiveSpot> active(double west, double south, double east, double north, int limit) {
        if (hasReporterColumns) {
            try {
                return activeRows(west, south, east, north, limit, true);
            } catch (BadSqlGrammarException e) {
                // 033 전 뷰(032)에는 reporter_count 가 없다 → 신고 수를 사람 수로 둔다
                hasReporterColumns = false;
                log.warn("reporter_count 칸이 없어 신고 수로 대신해요 (033 반영 전): {}", e.getMostSpecificCause().getMessage());
            }
        }
        return activeRows(west, south, east, north, limit, false);
    }

    private List<ActiveSpot> activeRows(double west, double south, double east, double north, int limit,
                                        boolean withReporter) {
        return jdbc.query("SELECT spot_id, report_type, lat, lon,"
                        + " DATE_FORMAT(starts_on, '%Y-%m-%d') AS starts, DATE_FORMAT(ends_on, '%Y-%m-%d') AS ends,"
                        + " report_count, " + (withReporter ? "reporter_count" : "report_count AS reporter_count")
                        + ", photo_count, confirmed, avoid_modes, cost_modes,"
                        + " DATE_FORMAT(last_at, '%Y-%m-%dT%H:%i') AS last, last_memo"
                        + " FROM road_report_active"
                        + " WHERE lon BETWEEN ? AND ? AND lat BETWEEN ? AND ?"
                        + " ORDER BY last_at DESC, spot_id DESC LIMIT ?",
                (rs, n) -> new ActiveSpot(rs.getLong("spot_id"), rs.getString("report_type"), rs.getDouble("lat"),
                        rs.getDouble("lon"), rs.getString("starts"), rs.getString("ends"), rs.getInt("report_count"),
                        rs.getInt("reporter_count"),
                        rs.getInt("photo_count"), rs.getInt("confirmed") == 1, modes(rs.getString("avoid_modes")),
                        modes(rs.getString("cost_modes")), rs.getString("last"), rs.getString("last_memo")),
                west, east, south, north, limit);
    }

    /**
     * 결정 59 보관 기간이 지난 사진·신고를 지운다 (하루 한 번, ReportCleanup).
     * 지울 번호를 먼저 읽고 번호로 지운다 — MySQL 은 같은 표를 읽으면서 지우는 DELETE 를 막을 수 있어서다.
     * 신고 줄을 지우면 사진은 표 규칙(ON DELETE CASCADE)으로 같이 지워진다.
     *
     * @return {지운 사진 장 수, 지운 신고 수}
     */
    public int[] cleanUp() {
        int photos = 0;
        for (List<Long> ids : batches(jdbc.queryForList(PHOTO_DUE, Long.class))) {
            photos += jdbc.update("DELETE FROM road_report_photo WHERE report_id IN (" + marks(ids.size()) + ")",
                    ids.toArray());
        }
        int rows = 0;
        for (List<Long> ids : batches(jdbc.queryForList(ROW_DUE, Long.class))) {
            rows += jdbc.update("DELETE FROM road_report WHERE id IN (" + marks(ids.size()) + ")", ids.toArray());
        }
        return new int[] {photos, rows};
    }

    private static List<List<Long>> batches(List<Long> ids) {
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += DELETE_BATCH) {
            out.add(ids.subList(i, Math.min(ids.size(), i + DELETE_BATCH)));
        }
        return out;
    }

    private static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    /** "senior,wheelchair" → [senior, wheelchair], 빈 칸이면 빈 목록 */
    static List<String> modes(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String m : csv.split(",")) {
            if (!m.isBlank()) {
                out.add(m.strip());
            }
        }
        return List.copyOf(out);
    }

    /** 사진 한 장. 신고가 없거나 숨긴 신고면 null */
    public NewReport.Photo photo(long reportId, int seq) {
        if (hasPhotoLabel) {
            try {
                return photoRow(reportId, seq, true);
            } catch (BadSqlGrammarException e) {
                hasPhotoLabel = false;
                log.warn("photo_label 칸이 없어 차량 신고 사진도 하루로 보여요 (036 반영 전): {}", e.getMostSpecificCause().getMessage());
            }
        }
        return photoRow(reportId, seq, false);
    }

    private NewReport.Photo photoRow(long reportId, int seq, boolean vehicle3h) {
        List<NewReport.Photo> found = jdbc.query(
                "SELECT p.content_type, p.data FROM road_report_photo p JOIN road_report r ON r.id = p.report_id"
                        + " WHERE p.report_id = ? AND p.seq = ? AND r.status <> 'hidden' AND " + stillKept(0, vehicle3h),
                (rs, n) -> new NewReport.Photo(rs.getString("content_type"), rs.getBytes("data")), reportId, seq);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String box(double w, double s, double e, double n) {
        return String.format(Locale.ROOT, "POLYGON((%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f))",
                w, s, e, s, e, n, w, n, w, s);
    }
}
