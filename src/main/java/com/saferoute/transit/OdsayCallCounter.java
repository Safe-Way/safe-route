package com.saferoute.transit;

import java.sql.Date;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ODsay 하루 부른 수를 DB 표(odsay_call_count, migration 035)에서 센다 (2026-11-16).
 * 서버 두 대가 같은 줄을 쓰므로 합쳐서 한도를 지키고, 배포·재시작해도 0으로 돌아가지 않는다.
 * 한도 안이면 하나 올리고 true, 이미 한도면 올리지 않고 false. 표가 없거나 DB를 못 쓰면 DataAccessException
 */
@Component
public class OdsayCallCounter {

    /** 그날 줄이 없으면 0으로 만든다 (두 서버가 같이 넣어도 하나만 생김) */
    static final String ENSURE_DAY = "INSERT IGNORE INTO odsay_call_count (day, calls) VALUES (?, 0)";

    /** 한도보다 적을 때만 하나 올린다. 한 문장이라 두 서버가 동시에 불러도 한도를 넘지 않는다 */
    static final String TAKE_ONE = "UPDATE odsay_call_count SET calls = calls + 1 WHERE day = ? AND calls < ?";

    private final JdbcTemplate jdbc;

    public OdsayCallCounter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean take(LocalDate day, int limit) {
        Date d = Date.valueOf(day);
        jdbc.update(ENSURE_DAY, d);
        return jdbc.update(TAKE_ONE, d, limit) == 1;
    }
}
