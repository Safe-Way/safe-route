package com.saferoute.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 진짜 DB 대신 가짜 DB(mock)로 UP·DOWN 두 경우를 확인 */
class HealthControllerTests {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HealthController controller = new HealthController(jdbc, "광주");

    @Test
    void upWhenDbAnswers() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("2026-10-28");

        ResponseEntity<HealthResponse> res = controller.health();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo(new HealthResponse("UP", "UP", "2026-10-28", "광주"));
    }

    @Test
    void downWhenDbFails() {
        when(jdbc.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new CannotGetJdbcConnectionException("DB 연결 실패"));

        ResponseEntity<HealthResponse> res = controller.health();

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().status()).isEqualTo("DOWN");
        assertThat(res.getBody().db()).isEqualTo("DOWN");
        assertThat(res.getBody().roadArea()).isEqualTo("광주");
    }
}
