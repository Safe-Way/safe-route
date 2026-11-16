package com.saferoute.route.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.saferoute.route.engine.event.RoadEvent;
import com.saferoute.route.engine.event.RoadReport;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;

/**
 * A파트의 poi 표(광주 정제 시설), holiday 표, data_source 표를 읽는다.
 * 시각은 TIME_TO_SEC로 분 단위로 읽는다 (24:00은 1440으로 들어온다).
 * 공휴일과 데이터 기준일은 처음 한 번 읽어 둔다 (적재가 바뀌면 서버를 다시 켠다).
 * 행사·돌발상황(road_event)은 자주 바뀌어 요청마다 읽는다. 신고(road_report_active 뷰)는 기억해 두고 표가 바뀔 때만 다시 읽는다.
 */
@Component
public class MysqlPoiSource implements PoiSource {

    private static final Logger log = LoggerFactory.getLogger(MysqlPoiSource.class);

    /**
     * 신고 뷰를 바뀐 표시가 같아도 다시 읽는 간격. 표시가 못 잡는 변경(DB에서 손으로 위치를 고친 것 등)의 안전망이다.
     * 뷰는 읽을 때마다 전체 신고를 다시 계산하고(재귀 CTE·신고끼리 거리), 대중교통·비서는 한 요청에 엔진을 여러 번 부른다
     */
    static final long REPORTS_TTL_NANOS = 60_000_000_000L;

    /**
     * 신고 뷰의 바뀐 표시. 서버가 두 대(app-1, app-2)라 신고를 받은 서버의 기억만 지우면 다른 서버는 모르므로, 요청마다 표를 직접 본다.
     * 숨기지 않은 신고의 개수·번호 합(추가·삭제·숨김 모두 바뀜)과 오늘 날짜(뷰가 ends_on >= CURDATE()로 거름).
     * 상태 색인(idx_road_report_status)만 읽어 표를 열지 않는다
     */
    static final String REPORTS_STAMP = "SELECT CONCAT(CURDATE(), ' ', COUNT(*), ' ', COALESCE(SUM(id), 0))"
            + " FROM road_report WHERE status <> 'hidden'";

    /** 읽어 둔 신고와 그때의 표시·시각. 한 덩어리로 바꿔야 다른 요청이 새 표시에 옛 목록을 짝지어 보지 않는다 */
    private record CachedReports(List<RoadReport> all, String stamp, long at) {
    }

    private final JdbcTemplate jdbc;
    private volatile Set<LocalDate> holidays;
    private volatile LocalDate dataDate;
    private volatile CachedReports reports;

    public MysqlPoiSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Poi> within(BBox box, Set<String> kinds) {
        if (kinds.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", Collections.nCopies(kinds.size(), "?"));
        Object[] args = new Object[kinds.size() + 1];
        args[0] = box.toWkt();
        int i = 1;
        for (String k : kinds) {
            args[i++] = k;
        }
        return jdbc.query("SELECT id, kind, sub, indoor, lon, lat, cnt,"
                + " TIME_TO_SEC(open_from) DIV 60 AS open_from, TIME_TO_SEC(open_to) DIV 60 AS open_to, open_days,"
                + " season_from, season_to,"
                + " TIME_TO_SEC(biz_from) DIV 60 AS biz_from, TIME_TO_SEC(biz_to) DIV 60 AS biz_to, biz_days, biz_sure"
                + " FROM poi WHERE MBRContains(ST_GeomFromText(?, 0), geom) AND kind IN (" + in + ")",
                (rs, n) -> new Poi(rs.getString("kind"), rs.getString("sub"), integer(rs, "indoor"),
                        rs.getDouble("lon"), rs.getDouble("lat"),
                        integer(rs, "open_from"), integer(rs, "open_to"), rs.getString("open_days"),
                        rs.getString("season_from"), rs.getString("season_to"),
                        integer(rs, "biz_from"), integer(rs, "biz_to"), rs.getString("biz_days"),
                        rs.getObject("biz_sure") == null ? null : rs.getInt("biz_sure") == 1,
                        rs.getLong("id"), Math.max(1, rs.getInt("cnt"))),
                args);
    }

    @Override
    public Set<LocalDate> holidays() {
        Set<LocalDate> h = holidays;
        if (h == null) {
            h = new HashSet<>(jdbc.queryForList("SELECT day FROM holiday", LocalDate.class));
            holidays = h;
        }
        return h;
    }

    @Override
    public LocalDate dataDate() {
        LocalDate d = dataDate;
        if (d == null) {
            LocalDateTime at = jdbc.queryForObject("SELECT MAX(loaded_at) FROM data_source", LocalDateTime.class);
            d = at == null ? null : at.toLocalDate();
            dataDate = d;
        }
        return d;
    }

    /** 끝을 모르는(ends_at NULL) 돌발은 다음 갱신까지 있다고 본다 (migration 026) */
    @Override
    public List<RoadEvent> events(BBox box, LocalDateTime at) {
        try {
            return jdbc.query("SELECT id, kind, title, place, message, lon, lat, radius_m, ends_at FROM road_event"
                    + " WHERE MBRContains(ST_GeomFromText(?, 0), geom)"
                    + " AND (starts_at IS NULL OR starts_at <= ?) AND (ends_at IS NULL OR ends_at >= ?)",
                    (rs, n) -> new RoadEvent(rs.getLong("id"), rs.getString("kind"), rs.getString("title"),
                            rs.getString("place"), rs.getString("message"), rs.getDouble("lon"), rs.getDouble("lat"),
                            rs.getDouble("radius_m"),
                            rs.getObject("ends_at", LocalDateTime.class)),
                    box.toWkt(), at, at);
        } catch (DataAccessException e) {
            // 행사 정보는 덧붙이는 것이라 못 읽어도 경로는 준다
            log.warn("행사·돌발상황을 읽지 못함: {}", e.getMostSpecificCause().getMessage());
            return List.of();
        }
    }

    /**
     * 뷰 전체를 읽어 기억하고 범위는 자바에서 거른다 (뷰라 공간 색인을 못 쓴다).
     * 요청마다 바뀐 표시(REPORTS_STAMP)를 먼저 읽어, 방금 들어온 신고가 '다시 찾기'에 바로 반영되게 한다 (어느 서버로 가도 같다).
     * shortcut: 신고가 수천 곳으로 늘어 전체 읽기가 느려지면 뷰를 범위로 읽거나 표로 바꾼다
     */
    @Override
    public List<RoadReport> reports(BBox box) {
        CachedReports cached = reports;
        try {
            String stamp = jdbc.queryForObject(REPORTS_STAMP, String.class);
            if (cached == null || !cached.stamp().equals(stamp) || System.nanoTime() - cached.at() > REPORTS_TTL_NANOS) {
                List<RoadReport> all = jdbc.query("SELECT spot_id, report_type, lon, lat, ends_on, avoid_modes, cost_modes"
                        + " FROM road_report_active",
                        (rs, n) -> new RoadReport(rs.getLong("spot_id"), rs.getString("report_type"),
                                rs.getDouble("lon"), rs.getDouble("lat"), rs.getObject("ends_on", LocalDate.class),
                                modes(rs.getString("avoid_modes")), modes(rs.getString("cost_modes"))));
                // 표시를 뷰보다 먼저 읽었으니 그 사이 신고가 들어와도 다음 요청에 표시가 달라 다시 읽는다
                cached = new CachedReports(all, stamp, System.nanoTime());
                reports = cached;
            }
        } catch (DataAccessException e) {
            // 신고는 덧붙이는 것이라 못 읽어도 경로는 준다 (행사와 같다). 이미 읽어 둔 목록이 있으면 낡아도 그것을 써서
            // DB가 잠깐 끊긴 사이 피하던 신고가 사라지지 않게 한다. 기억은 그대로라 다음 요청에 다시 읽는다
            log.warn("신고를 읽지 못함: {}", e.getMostSpecificCause().getMessage());
            if (cached == null) {
                return List.of();
            }
        }
        return cached.all().stream().filter(r -> box.contains(r.lon(), r.lat())).toList();
    }

    /** 뷰의 쉼표 목록 ("senior,wheelchair", 빈 글자면 없음) */
    static List<String> modes(String csv) {
        return csv == null ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(m -> !m.isEmpty()).toList();
    }

    private static Integer integer(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).intValue();
    }
}
