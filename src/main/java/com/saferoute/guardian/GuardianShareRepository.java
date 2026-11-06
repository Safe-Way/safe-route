package com.saferoute.guardian;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * guardian_share 표 (029). 서버가 두 대라 메모리가 아니라 이 표에 잠깐 둔다.
 * 위치는 마지막 한 점만 덮어쓰고, 끝나면 위치 칸을 비운다. 끝난 지 10분 지난 줄은 지운다.
 */
@Repository
public class GuardianShareRepository {

    private static final String COLUMNS = "token, guest_id, walker_name, dest_name, dest_lat, dest_lon, mode, "
            + "started_at, expires_at, last_lat, last_lon, last_at, remain_m, eta_min, off_route, ended_at, end_reason";

    private final JdbcTemplate jdbc;

    public GuardianShareRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(GuardianShare s) {
        jdbc.update("INSERT INTO guardian_share (token, guest_id, walker_name, dest_name, dest_lat, dest_lon, mode, "
                        + "started_at, expires_at, last_lat, last_lon, last_at, remain_m) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                s.token(), s.guestId(), s.walkerName(), s.destName(), s.destLat(), s.destLon(), s.mode(),
                ts(s.startedAt()), ts(s.expiresAt()), s.lastLat(), s.lastLon(), ts(s.lastAt()), s.remainM());
    }

    Optional<GuardianShare> find(String token) {
        List<GuardianShare> rows = jdbc.query("SELECT " + COLUMNS + " FROM guardian_share WHERE token = ?",
                GuardianShareRepository::row, token);
        return rows.stream().findFirst();
    }

    /** 위치 덮어쓰기. 아직 안 끝난 공유만 바뀐다. 바뀐 줄 수를 돌려줌 */
    int move(String token, double lat, double lon, LocalDateTime at, Integer remainM, Integer etaMin,
             boolean offRoute) {
        return jdbc.update("UPDATE guardian_share SET last_lat = ?, last_lon = ?, last_at = ?, remain_m = ?, "
                        + "eta_min = ?, off_route = ? WHERE token = ? AND ended_at IS NULL",
                lat, lon, ts(at), remainM, etaMin, offRoute ? 1 : 0, token);
    }

    /** 끝내기: 위치 칸을 비우고 끝난 시각·이유를 적는다 (지나온 길은 원래 없음) */
    int end(String token, String reason, LocalDateTime at) {
        return jdbc.update("UPDATE guardian_share SET ended_at = ?, end_reason = ?, last_lat = NULL, "
                + "last_lon = NULL WHERE token = ? AND ended_at IS NULL", ts(at), reason, token);
    }

    /** 같은 게스트의 다른 진행 중 공유를 끝낸다 (새로 시작하면 옛 링크는 닫힘) */
    int endOthers(String guestId, String keepToken, LocalDateTime at) {
        return jdbc.update("UPDATE guardian_share SET ended_at = ?, end_reason = 'stopped', last_lat = NULL, "
                + "last_lon = NULL WHERE guest_id = ? AND token <> ? AND ended_at IS NULL", ts(at), guestId, keepToken);
    }

    /** 만료: 시작 3시간이 지났거나, 위치가 30분 동안 안 온 공유를 끝낸다 */
    int expire(LocalDateTime now, LocalDateTime silentSince) {
        return jdbc.update("UPDATE guardian_share SET ended_at = ?, end_reason = 'expired', last_lat = NULL, "
                        + "last_lon = NULL WHERE ended_at IS NULL AND (expires_at <= ? "
                        + "OR COALESCE(last_at, started_at) <= ?)",
                ts(now), ts(now), ts(silentSince));
    }

    /** 끝난 지 오래된 줄 지우기 */
    int deleteEndedBefore(LocalDateTime before) {
        return jdbc.update("DELETE FROM guardian_share WHERE ended_at IS NOT NULL AND ended_at <= ?", ts(before));
    }

    /** 내 기록 지우기 때 그 게스트의 공유를 모두 지운다 */
    public int deleteByGuest(String guestId) {
        return jdbc.update("DELETE FROM guardian_share WHERE guest_id = ?", guestId);
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    private static LocalDateTime time(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer num(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static GuardianShare row(ResultSet rs, int i) throws SQLException {
        return new GuardianShare(rs.getString("token"), rs.getString("guest_id"), rs.getString("walker_name"),
                rs.getString("dest_name"), dbl(rs, "dest_lat"), dbl(rs, "dest_lon"), rs.getString("mode"),
                time(rs, "started_at"), time(rs, "expires_at"), dbl(rs, "last_lat"), dbl(rs, "last_lon"),
                time(rs, "last_at"), num(rs, "remain_m"), num(rs, "eta_min"), rs.getInt("off_route") == 1,
                time(rs, "ended_at"), rs.getString("end_reason"));
    }
}
