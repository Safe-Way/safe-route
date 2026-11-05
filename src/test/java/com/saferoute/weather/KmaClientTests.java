package com.saferoute.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.ZonedDateTime;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 가짜 기상청 서버로 요청 모양과 응답 읽기를 확인 (인터넷 없이 돈다) */
class KmaClientTests {

    // 기상청 초단기실황 응답을 줄인 것: 기온 12.3도, 1시간 강수량 1.5mm, 강수형태 1(비)
    private static final String KMA_JSON = """
            { "response": {
                "header": { "resultCode": "00", "resultMsg": "NORMAL_SERVICE" },
                "body": { "items": { "item": [
                  { "baseDate": "20261103", "baseTime": "1000", "category": "PTY", "obsrValue": "1" },
                  { "baseDate": "20261103", "baseTime": "1000", "category": "RN1", "obsrValue": "1.5" },
                  { "baseDate": "20261103", "baseTime": "1000", "category": "T1H", "obsrValue": "12.3" },
                  { "baseDate": "20261103", "baseTime": "1000", "category": "REH", "obsrValue": "80" }
                ] } } } }
            """;

    private static final String ERROR_JSON = """
            { "response": { "header": { "resultCode": "10", "resultMsg": "잘못된 요청 파라메터 에러." } } }
            """;

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 3, 10, 50, 0, 0, KmaClient.KOREA);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KmaClient client = new KmaClient(builder, "test-key");

    @Test
    void baseTimeWaitsFortyMinutes() {
        assertThat(KmaClient.baseTime(NOW.withMinute(39)).getHour()).isEqualTo(9);
        assertThat(KmaClient.baseTime(NOW.withMinute(41)).getHour()).isEqualTo(10);
        assertThat(KmaClient.baseTime(NOW.withHour(0).withMinute(10)).getDayOfMonth()).isEqualTo(2);
    }

    @Test
    void sendsGridAndReadsValues() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr" + KmaClient.PATH)))
                .andExpect(queryParam("serviceKey", "test-key"))
                .andExpect(queryParam("base_date", "20261103"))
                .andExpect(queryParam("base_time", "1000"))
                .andExpect(queryParam("nx", "60")).andExpect(queryParam("ny", "74"))
                .andRespond(withSuccess(KMA_JSON, MediaType.APPLICATION_JSON));

        WeatherNow w = client.now(new KmaGrid(60, 74), NOW);

        assertThat(w).isEqualTo(new WeatherNow(12.3, 1.5, 1, "202611031000"));
        server.verify();
    }

    @Test
    void errorCodeBecomesWeatherUnavailable() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr")))
                .andRespond(withSuccess(ERROR_JSON, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.now(new KmaGrid(60, 74), NOW))
                .isInstanceOf(WeatherUnavailableException.class)
                .hasMessageContaining("10");
    }

    @Test
    void serverErrorBecomesWeatherUnavailable() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.now(new KmaGrid(60, 74), NOW))
                .isInstanceOf(WeatherUnavailableException.class);
    }

    @Test
    void missingKeyFailsWithoutCallingKma() {
        KmaClient noKey = new KmaClient(builder, "");

        assertThatThrownBy(() -> noKey.now(new KmaGrid(60, 74), NOW))
                .isInstanceOf(WeatherUnavailableException.class);
        server.verify();
    }
}
