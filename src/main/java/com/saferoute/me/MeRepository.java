package com.saferoute.me;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 내 정보·다닌 기록 읽고 쓰기 (재휘님 표 025·027·028·029).
 * user_profile·user_profile_now·user_map_layer·trip_log, 지울 때는 walk_profile·guardian_share 도.
 */
@Repository
public class MeRepository {

    /** 한 사람이 남기는 다닌 기록 수 (027 표 설명: 최근 200건) */
    static final int KEEP_TRIPS = 200;

    /** 고칠 수 있는 칸 (칸 이름을 SQL 에 넣으므로 이 목록만 받는다) */
    static final List<String> COLUMNS = List.of("mode", "mobility_aid", "stairs_ok", "max_detour", "rest_every_m",
            "prefer_transit", "consent_trips", "consent_at", "temp_mode", "temp_aid", "temp_reason", "temp_until",
            "next_ask_on", "setup_done");

    private final JdbcTemplate jdbc;

    public MeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 내 정보 한 줄 (user_profile_now 뷰 + 원래 표). 없으면 빈 값 */
    public Optional<Row> find(String guestId) {
        List<Row> rows = jdbc.query("SELECT n.mode_now, n.aid_now, n.temp_active, n.setup_done, n.ask_today,"
                        + " u.mode, u.mobility_aid, u.stairs_ok, u.max_detour, u.rest_every_m, u.prefer_transit,"
                        + " u.temp_mode, u.temp_aid, u.temp_reason, u.temp_until, u.consent_trips"
                        + " FROM user_profile u JOIN user_profile_now n ON n.guest_id = u.guest_id"
                        + " WHERE u.guest_id = ?",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getBoolean(5), rs.getString(6), rs.getString(7), rs.getObject(8, Integer.class),
                        rs.getObject(9) == null ? null : rs.getDouble(9), rs.getObject(10, Integer.class),
                        rs.getObject(11, Integer.class), rs.getString(12), rs.getString(13), rs.getString(14),
                        date(rs.getDate(15)), rs.getBoolean(16)),
                guestId);
        return rows.stream().findFirst();
    }

    /** 내 정보 원래 값 */
    public record Row(String modeNow, String aidNow, boolean tempActive, boolean setupDone, boolean askToday,
                      String mode, String mobilityAid, Integer stairsOk, Double maxDetour, Integer restEveryM,
                      Integer preferTransit, String tempMode, String tempAid, String tempReason, LocalDate tempUntil,
                      boolean consentTrips) {
    }

    /** 보낸 칸만 바꾸고, 줄이 없으면 만든다. 값이 null 인 칸은 "비우기" 뜻 */
    public void save(String guestId, Map<String, Object> fields) {
        Map<String, Object> f = new LinkedHashMap<>();
        fields.forEach((k, v) -> {
            if (COLUMNS.contains(k)) {
                f.put(k, v);
            }
        });
        if (f.isEmpty()) {
            ensure(guestId);
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
            upd.append(upd.isEmpty() ? "" : ", ").append(e.getKey()).append(" = VALUES(").append(e.getKey()).append(')');
            args.add(e.getValue() instanceof LocalDate d ? Date.valueOf(d) : e.getValue());
        }
        jdbc.update("INSERT INTO user_profile (" + cols + ") VALUES (" + qs + ") ON DUPLICATE KEY UPDATE " + upd,
                args.toArray());
    }

    /** 줄이 없으면 빈 줄을 만든다 (지도 설정은 user_profile 줄이 있어야 남길 수 있음) */
    public void ensure(String guestId) {
        jdbc.update("INSERT IGNORE INTO user_profile (guest_id) VALUES (?)", guestId);
    }

    /** 기간이 남은 내 지도 설정 (user_map_layer_now 뷰) */
    public List<MeRequests.Layer> layers(String guestId) {
        return jdbc.query("SELECT layer, name, shown, until, reason FROM user_map_layer_now WHERE guest_id = ?"
                        + " ORDER BY layer",
                (rs, i) -> new MeRequests.Layer(rs.getString(1), rs.getString(2), rs.getBoolean(3),
                        rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate().toString(), rs.getString(5)),
                guestId);
    }

    /** 지도 시설 사전에 있는 이름인지 */
    public boolean layerExists(String layer) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM map_layer WHERE layer = ?", Integer.class, layer);
        return n != null && n > 0;
    }

    public void saveLayer(String guestId, String layer, boolean shown, LocalDate until, String reason) {
        jdbc.update("INSERT INTO user_map_layer (guest_id, layer, shown, until, reason) VALUES (?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE shown = VALUES(shown), until = VALUES(until), reason = VALUES(reason)",
                guestId, layer, shown ? 1 : 0, until == null ? null : Date.valueOf(until), reason);
    }

    /** 모드 기본으로 되돌리기 */
    public int deleteLayer(String guestId, String layer) {
        return jdbc.update("DELETE FROM user_map_layer WHERE guest_id = ? AND layer = ?", guestId, layer);
    }

    public int tripCount(String guestId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM trip_log WHERE guest_id = ?", Integer.class, guestId);
        return n == null ? 0 : n;
    }

    /** 기록 한 줄 넣고 번호를 돌려준다 */
    public long insertTrip(String guestId, LocalDate on, int weekday, LocalTime slot, Trip t) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO trip_log (guest_id, started_on, weekday, start_slot, to_name,"
                    + " to_lat, to_lon, mode, travel, route_kind, by_assistant, weather, night, body_note, planned_min)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[] {"id"});
            ps.setString(1, guestId);
            ps.setDate(2, Date.valueOf(on));
            ps.setInt(3, weekday);
            ps.setTime(4, Time.valueOf(slot));
            ps.setString(5, t.toName());
            ps.setDouble(6, t.toLat());
            ps.setDouble(7, t.toLon());
            ps.setString(8, t.mode());
            ps.setString(9, t.travel());
            ps.setString(10, t.routeKind());
            ps.setInt(11, t.byAssistant() ? 1 : 0);
            ps.setString(12, t.weather());
            ps.setInt(13, t.night() ? 1 : 0);
            ps.setString(14, t.bodyNote());
            ps.setObject(15, t.plannedMin());
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? 0 : id.longValue();
    }

    /** 넣을 기록 (이미 고르고 반올림한 값) */
    public record Trip(String toName, double toLat, double toLon, String mode, String travel, String routeKind,
                       boolean byAssistant, String weather, boolean night, String bodyNote, Integer plannedMin) {
    }

    /** 최근 KEEP_TRIPS 건만 남기고 지운다 */
    public int trimTrips(String guestId) {
        return jdbc.update("DELETE FROM trip_log WHERE guest_id = ? AND id NOT IN (SELECT id FROM (SELECT id FROM"
                + " trip_log WHERE guest_id = ? ORDER BY id DESC LIMIT " + KEEP_TRIPS + ") keep)", guestId, guestId);
    }

    /** 결과·한마디 적기. 내 기록이 아니면 0 */
    public int endTrip(String guestId, long id, String result, Integer actualMin, String feedback, String reason) {
        return jdbc.update("UPDATE trip_log SET result = ?, actual_min = ?, feedback = ?, feedback_reason = ?"
                + " WHERE id = ? AND guest_id = ?", result, actualMin, feedback, reason, id, guestId);
    }

    /** 최근 끝난 기록 (도착·그만둠, 최근 것부터) */
    public List<PreferenceLearner.Done> recentDone(String guestId, int limit) {
        return jdbc.query("SELECT travel, result, feedback, feedback_reason FROM trip_log"
                        + " WHERE guest_id = ? AND result IN ('arrived', 'stopped') ORDER BY id DESC LIMIT " + limit,
                (rs, i) -> new PreferenceLearner.Done(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)),
                guestId);
    }

    public int deleteTrips(String guestId) {
        return jdbc.update("DELETE FROM trip_log WHERE guest_id = ?", guestId);
    }

    /**
     * 내 기록 모두 지우기. 지도 설정·다닌 기록은 user_profile 을 지우면 같이 지워지지만(외래 키),
     * 몇 줄 지웠는지 알려 주려고 먼저 지운다.
     */
    public MeRequests.Deleted deleteAll(String guestId) {
        int trips = deleteTrips(guestId);
        int layers = jdbc.update("DELETE FROM user_map_layer WHERE guest_id = ?", guestId);
        boolean profile = jdbc.update("DELETE FROM user_profile WHERE guest_id = ?", guestId) > 0;
        int walk = jdbc.update("DELETE FROM walk_profile WHERE guest_id = ?", guestId);
        int shares = jdbc.update("DELETE FROM guardian_share WHERE guest_id = ?", guestId);
        return new MeRequests.Deleted(profile, trips, layers, walk, shares);
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
