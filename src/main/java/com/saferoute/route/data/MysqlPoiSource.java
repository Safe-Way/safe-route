package com.saferoute.route.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;

/**
 * A파트의 poi 표(광주 정제 시설), holiday 표, data_source 표를 읽는다.
 * 시각은 TIME_TO_SEC로 분 단위로 읽는다 (24:00은 1440으로 들어온다).
 * 공휴일과 데이터 기준일은 처음 한 번 읽어 둔다 (적재가 바뀌면 서버를 다시 켠다).
 * 행사·돌발상황(road_event)은 자주 바뀌어 요청마다 읽는다.
 */
@Component
public class MysqlPoiSource implements PoiSource {

    private static final Logger log = LoggerFactory.getLogger(MysqlPoiSource.class);

    private final JdbcTemplate jdbc;
    private volatile Set<LocalDate> holidays;
    private volatile LocalDate dataDate;

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

    private static Integer integer(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).intValue();
    }
}
