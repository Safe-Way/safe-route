package com.saferoute.event;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** road_event 표 (A 파트 026) 읽기 */
@Repository
public class EventRepository {

    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 범위 안에서 지금 진행 중인 행사·돌발 (시작 전이거나 끝난 것은 뺀다). 시작을 모르면 진행 중으로 본다.
     *
     * @param now 한국 시각 지금
     */
    public List<RoadEvent> active(double west, double south, double east, double north, LocalDateTime now,
            int limit) {
        Timestamp t = Timestamp.valueOf(now);
        return jdbc.query("SELECT id, kind, detail, title, place, lat, lon, radius_m,"
                        + " DATE_FORMAT(starts_at, '%Y-%m-%dT%H:%i') AS s, DATE_FORMAT(ends_at, '%Y-%m-%dT%H:%i') AS e,"
                        + " message, url FROM road_event"
                        + " WHERE MBRContains(ST_GeomFromText(?, 0), geom)"
                        + " AND (starts_at IS NULL OR starts_at <= ?) AND (ends_at IS NULL OR ends_at >= ?)"
                        + " ORDER BY starts_at, id LIMIT ?",
                (rs, i) -> new RoadEvent(rs.getLong("id"), rs.getString("kind"), rs.getString("detail"),
                        rs.getString("title"), rs.getString("place"), rs.getDouble("lat"), rs.getDouble("lon"),
                        rs.getInt("radius_m"), rs.getString("s"), rs.getString("e"), rs.getString("message"),
                        rs.getString("url")),
                box(west, south, east, north), t, t, limit);
    }

    private static String box(double w, double s, double e, double n) {
        return String.format(Locale.ROOT, "POLYGON((%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f,%.7f %.7f))",
                w, s, e, s, e, n, w, n, w, s);
    }
}
