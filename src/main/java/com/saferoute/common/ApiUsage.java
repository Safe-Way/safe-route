package com.saferoute.common;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 외부 API 하루 호출 수 (2026-11-24, 공개 배포 준비: 사용량 알림). api_call_count 표(V40)에 서버 두 대가 같이 더한다.
 * 클라이언트가 {@link #counter(String)} 를 RestClient 에 끼우면 나가는 요청마다 하나씩 센다(실패한 요청도 한도에 들어가므로 셈).
 * ODsay 는 한도를 지키는 odsay_call_count(035)가 이미 센다. 세기가 실패해도 API 호출은 막지 않는다.
 */
@Component
public class ApiUsage {

    private static final Logger log = LoggerFactory.getLogger(ApiUsage.class);
    static final String ADD = "INSERT INTO api_call_count (day, api, calls) VALUES (?, ?, 1)"
            + " ON DUPLICATE KEY UPDATE calls = calls + 1";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicBoolean warned = new AtomicBoolean();

    public ApiUsage(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 이 RestClient 로 나가는 요청을 api 이름으로 센다 */
    public ClientHttpRequestInterceptor counter(String api) {
        return (request, body, execution) -> {
            record(api);
            return execution.execute(request, body);
        };
    }

    void record(String api) {
        try {
            jdbc.update(ADD, today(), api);
        } catch (DataAccessException e) {
            if (warned.compareAndSet(false, true)) {
                log.warn("API 호출 수를 세지 못함 (api_call_count 표가 없거나 DB 오류, V40 적용 확인): {}",
                        e.getClass().getSimpleName());
            }
        }
    }

    /** 오늘(한국 날짜) API 이름 → 호출 수. odsay 는 odsay_call_count 에서 */
    public Map<String, Integer> todayCalls() {
        Map<String, Integer> out = new HashMap<>();
        LocalDate d = today();
        try {
            jdbc.query("SELECT api, calls FROM api_call_count WHERE day = ?",
                    rs -> {
                        out.put(rs.getString("api"), rs.getInt("calls"));
                    }, d);
        } catch (DataAccessException e) {
            log.warn("API 호출 수를 읽지 못함: {}", e.getClass().getSimpleName());
        }
        try {
            jdbc.query("SELECT calls FROM odsay_call_count WHERE day = ?", rs -> {
                out.put("odsay", rs.getInt("calls"));
            }, d);
        } catch (DataAccessException e) {
            log.warn("ODsay 호출 수를 읽지 못함: {}", e.getClass().getSimpleName());
        }
        return out;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
