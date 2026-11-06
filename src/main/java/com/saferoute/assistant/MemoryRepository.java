package com.saferoute.assistant;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 비서가 기억하는 것 읽고 쓰기 (재휘님 표 027·028).
 * user_profile·user_profile_now·trip_habit·trip_weather_choice·trip_log·map_layer·user_map_layer.
 * 좌표는 trip_log 의 도착지(약 100m 반올림)만 읽는다. 대화 내용은 어디에도 쓰지 않는다.
 */
@Repository
public class MemoryRepository {

    private final JdbcTemplate jdbc;

    public MemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 내 정보. 줄이 없으면 null (처음 쓰는 사람) */
    public Profile profile(String guestId) {
        List<Profile> rows = jdbc.query("SELECT p.mode_now, p.aid_now, p.temp_active, p.temp_reason, p.temp_until,"
                        + " p.mode, p.stairs_ok, p.prefer_transit, p.setup_done, u.consent_trips"
                        + " FROM user_profile_now p JOIN user_profile u ON u.guest_id = p.guest_id WHERE p.guest_id = ?",
                (rs, i) -> new Profile(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                        date(rs.getDate(5)), rs.getString(6), (Integer) rs.getObject(7, Integer.class),
                        (Integer) rs.getObject(8, Integer.class), rs.getBoolean(9), rs.getBoolean(10)),
                guestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 내 정보 고치기. 값이 들어 있는 칸만 바꾸고, 줄이 없으면 만든다.
     *
     * @param fields 칸 이름 → 값. 쓸 수 있는 칸은 PROFILE_COLUMNS 만
     */
    public void saveProfile(String guestId, Map<String, Object> fields) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (PROFILE_COLUMNS.contains(e.getKey())) {
                f.put(e.getKey(), e.getValue());
            }
        }
        if (f.isEmpty()) {
            return;
        }
        StringBuilder cols = new StringBuilder("guest_id");
        StringBuilder qs = new StringBuilder("?");
        StringBuilder upd = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(guestId);
        for (Map.Entry<String, Object> e : f.entrySet()) {
            cols.append(", ").append(e.getKey());
            qs.append(", ?");
            upd.append(upd.isEmpty() ? "" : ", ").append(e.getKey()).append(" = VALUES(").append(e.getKey()).append(")");
            args.add(e.getValue() instanceof LocalDate d ? Date.valueOf(d) : e.getValue());
        }
        jdbc.update("INSERT INTO user_profile (" + cols + ") VALUES (" + qs + ") ON DUPLICATE KEY UPDATE " + upd,
                args.toArray());
    }

    /** 고칠 수 있는 내 정보 칸 (칸 이름을 SQL 에 넣으므로 이 목록만 받는다) */
    static final List<String> PROFILE_COLUMNS = List.of("mode", "mobility_aid", "stairs_ok", "prefer_transit",
            "temp_mode", "temp_aid", "temp_reason", "temp_until", "next_ask_on", "setup_done");

    /** 습관: 같은 요일·시간대에 같은 곳을 몇 번 갔나 (trip_habit 뷰) */
    public record Habit(String toName, double lat, double lon, LocalTime slot, int times, int byTransit, int byWalk) {
    }

    /** 이 요일에 from~to 시간대에 3번 이상 간 곳, 많이 간 순 */
    public List<Habit> habits(String guestId, int weekday, LocalTime from, LocalTime to) {
        return jdbc.query("SELECT to_name, to_lat, to_lon, start_slot, times, by_transit, by_walk FROM trip_habit"
                        + " WHERE guest_id = ? AND weekday = ? AND start_slot BETWEEN ? AND ? AND times >= 3"
                        + " ORDER BY times DESC, start_slot LIMIT 3",
                (rs, i) -> new Habit(rs.getString(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getTime(4).toLocalTime(), rs.getInt(5), rs.getInt(6), rs.getInt(7)),
                guestId, weekday, java.sql.Time.valueOf(from), java.sql.Time.valueOf(to));
    }

    /** 날씨에 따라 고른 것 (trip_weather_choice 뷰). weather = rain, heat, cold, dust, clear */
    public record WeatherChoice(int trips, int byTransit, int byWalk, int badFeedback) {
    }

    public WeatherChoice weatherChoice(String guestId, String weather) {
        List<WeatherChoice> r = jdbc.query("SELECT trips, by_transit, by_walk, bad_feedback FROM trip_weather_choice"
                        + " WHERE guest_id = ? AND weather = ?",
                (rs, i) -> new WeatherChoice(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)),
                guestId, weather);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 다녀온 곳 이름 중 query 가 들어 있는 곳, 많이 간 순 ("집", "시장" → "남광주시장") */
    public List<Conversation.Place> visited(String guestId, String query) {
        return jdbc.query("SELECT to_name, MAX(to_lat), MAX(to_lon), COUNT(*) AS n FROM trip_log"
                        + " WHERE guest_id = ? AND result <> 'stopped' AND to_name LIKE CONCAT('%', ?, '%')"
                        + " GROUP BY to_name ORDER BY n DESC LIMIT 3",
                (rs, i) -> new Conversation.Place(rs.getString(1), null, rs.getDouble(2), rs.getDouble(3)),
                guestId, query);
    }

    /** 지도 시설 사전 한 줄 (map_layer) */
    public record Layer(String layer, String name, List<String> aliases) {
    }

    public List<Layer> layers() {
        return jdbc.query("SELECT layer, name, aliases FROM map_layer ORDER BY layer",
                (rs, i) -> new Layer(rs.getString(1), rs.getString(2), split(rs.getString(3))));
    }

    /** 내 지도 설정 더하기(shown=1)·빼기(shown=0). until 이 null 이면 바꿀 때까지 */
    public void saveLayer(String guestId, String layer, boolean shown, LocalDate until, String reason) {
        jdbc.update("INSERT INTO user_map_layer (guest_id, layer, shown, until, reason) VALUES (?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE shown = VALUES(shown), until = VALUES(until), reason = VALUES(reason)",
                guestId, layer, shown ? 1 : 0, until == null ? null : Date.valueOf(until), reason);
    }

    private static List<String> split(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return out;
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
