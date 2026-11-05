package com.saferoute.walk;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** walk_profile · walk_speed_sample 표 읽고 쓰기 (025_walk_profile.sql) */
@Repository
public class WalkSpeedRepository {

    /** 표에서 읽은 지금 속도 줄 */
    record Profile(Double speedMMin, int samples) {
    }

    /** 남아 있는 측정의 개수와 속도 합 */
    record SampleSum(int count, double sum) {
    }

    private final JdbcTemplate jdbc;

    public WalkSpeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 게스트·모드 줄이 없으면 만들고, 있으면 다른 요청이 못 건드리게 잠근다 (같은 트랜잭션 안에서 불러야 함) */
    void lockProfile(String guestId, String mode) {
        jdbc.update("INSERT IGNORE INTO walk_profile (guest_id, mode) VALUES (?, ?)", guestId, mode);
        jdbc.queryForObject("SELECT samples FROM walk_profile WHERE guest_id = ? AND mode = ? FOR UPDATE",
                Integer.class, guestId, mode);
    }

    void insertSample(String guestId, String mode, int distanceM, int movingS, double speedMMin, LocalDate day) {
        jdbc.update("""
                INSERT INTO walk_speed_sample (guest_id, mode, distance_m, moving_s, speed_m_min, measured_on)
                VALUES (?, ?, ?, ?, ?, ?)""", guestId, mode, distanceM, movingS, speedMMin, day);
    }

    /** 최근 keep 개만 남기고 오래된 측정을 지운다 */
    void trimSamples(String guestId, String mode, int keep) {
        List<Long> cut = jdbc.queryForList("""
                SELECT id FROM walk_speed_sample WHERE guest_id = ? AND mode = ?
                ORDER BY id DESC LIMIT 1 OFFSET ?""", Long.class, guestId, mode, keep - 1);
        if (!cut.isEmpty()) {
            jdbc.update("DELETE FROM walk_speed_sample WHERE guest_id = ? AND mode = ? AND id < ?",
                    guestId, mode, cut.get(0));
        }
    }

    SampleSum sampleSum(String guestId, String mode) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS n, COALESCE(SUM(speed_m_min), 0) AS s
                FROM walk_speed_sample WHERE guest_id = ? AND mode = ?""",
                (rs, i) -> new SampleSum(rs.getInt("n"), rs.getDouble("s")), guestId, mode);
    }

    void updateProfile(String guestId, String mode, double speedMMin, int distanceM, int movingS) {
        jdbc.update("""
                UPDATE walk_profile
                SET speed_m_min = ?, samples = LEAST(samples + 1, 32767),
                    walked_m = LEAST(walked_m + ?, 2000000000), moving_s = LEAST(moving_s + ?, 2000000000)
                WHERE guest_id = ? AND mode = ?""", speedMMin, distanceM, movingS, guestId, mode);
    }

    /** 없으면 null */
    Profile find(String guestId, String mode) {
        List<Profile> rows = jdbc.query("SELECT speed_m_min, samples FROM walk_profile WHERE guest_id = ? AND mode = ?",
                (rs, i) -> new Profile(rs.getObject("speed_m_min") == null ? null : rs.getDouble("speed_m_min"),
                        rs.getInt("samples")),
                guestId, mode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 내 기록 지우기: 모든 모드의 줄을 지운다 (측정 줄은 FK CASCADE 로 같이 지워짐). 지운 줄 수 */
    int deleteGuest(String guestId) {
        return jdbc.update("DELETE FROM walk_profile WHERE guest_id = ?", guestId);
    }
}
