package com.saferoute.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서버 상태 확인 주소. 브라우저에 http://localhost:8080/api/health 를 넣으면 바로 볼 수 있다.
 * DB에 물어봐서 대답이 오면 UP, 연결이 안 되면 DOWN 과 503 을 준다.
 */
@RestController
public class HealthController {

    /** data_source 표에서 가장 최근에 적재한 날짜를 "2026-10-28" 모양으로 꺼낸다 */
    static final String DATA_DATE_SQL =
            "SELECT DATE_FORMAT(MAX(loaded_at), '%Y-%m-%d') FROM data_source";

    private final JdbcTemplate jdbc;
    private final String roadArea;

    public HealthController(JdbcTemplate jdbc,
                            @Value("${health.road-area:광주}") String roadArea) {
        this.jdbc = jdbc;
        this.roadArea = roadArea;
    }

    @GetMapping("/api/health")
    public ResponseEntity<HealthResponse> health() {
        try {
            String dataDate = jdbc.queryForObject(DATA_DATE_SQL, String.class);
            return ResponseEntity.ok(new HealthResponse("UP", "UP", dataDate, roadArea));
        } catch (DataAccessException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthResponse("DOWN", "DOWN", null, roadArea));
        }
    }
}
