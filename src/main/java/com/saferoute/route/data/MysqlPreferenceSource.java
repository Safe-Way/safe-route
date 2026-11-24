package com.saferoute.route.data;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * user_profile 표에서 stairs_ok·max_detour·rest_every_m을 읽는다 (027).
 * 선호는 덧붙이는 것이라 못 읽어도 모드 기준으로 경로를 준다. 게스트 번호는 로그에 쓰지 않는다 (NFR-07).
 */
@Component
public class MysqlPreferenceSource implements PreferenceSource {

    private static final Logger log = LoggerFactory.getLogger(MysqlPreferenceSource.class);

    private final JdbcTemplate jdbc;

    public MysqlPreferenceSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserPreferences find(String guestId) {
        if (guestId == null || guestId.isBlank()) {
            return UserPreferences.NONE;
        }
        try {
            List<UserPreferences> rows = jdbc.query(
                    "SELECT stairs_ok, max_detour, rest_every_m FROM user_profile WHERE guest_id = ?",
                    (rs, n) -> new UserPreferences(
                            rs.getObject("stairs_ok") == null ? null : rs.getInt("stairs_ok"),
                            rs.getObject("max_detour") == null ? null
                                    : ((BigDecimal) rs.getObject("max_detour")).doubleValue(),
                            rs.getObject("rest_every_m") == null ? null : rs.getInt("rest_every_m")),
                    guestId.trim().toLowerCase(java.util.Locale.ROOT)); // 게스트 칸은 대소문자 구별 (V38)
            return rows.isEmpty() ? UserPreferences.NONE : rows.get(0);
        } catch (DataAccessException e) {
            log.warn("경로 선호를 읽지 못함: {}", e.getMostSpecificCause().getMessage());
            return UserPreferences.NONE;
        }
    }
}
