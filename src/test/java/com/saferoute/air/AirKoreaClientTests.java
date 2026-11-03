package com.saferoute.air;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 가짜 에어코리아 서버로 요청 모양과 응답 읽기를 확인 (인터넷 없이 돈다) */
class AirKoreaClientTests {

    // 에어코리아 응답 모양을 줄인 것: 측정소 3곳, 한 곳은 초미세먼지가 "-"(측정 안 됨)
    private static final String AIR_JSON = """
            { "response": {
                "header": { "resultCode": "00", "resultMsg": "NORMAL_CODE" },
                "body": { "totalCount": 3, "items": [
                  { "stationName": "서석동", "pm10Value": "40", "pm25Value": "20", "dataTime": "2026-11-02 14:00" },
                  { "stationName": "농성동", "pm10Value": "90", "pm25Value": "-", "dataTime": "2026-11-02 14:00" },
                  { "stationName": "두암동", "pm10Value": "", "pm25Value": "30", "dataTime": "2026-11-02 14:00" }
                ] } } }
            """;

    private static final String ERROR_JSON = """
            { "response": { "header": { "resultCode": "30", "resultMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR" } } }
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AirKoreaClient client = new AirKoreaClient(builder, "test-key");

    @Test
    void sendsSidoAndReadsStations() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr" + AirKoreaClient.PATH)))
                .andExpect(queryParam("serviceKey", "test-key"))
                .andExpect(queryParam("returnType", "json"))
                .andExpect(queryParam("sidoName", "%EA%B4%91%EC%A3%BC"))
                .andRespond(withSuccess(AIR_JSON, MediaType.APPLICATION_JSON));

        List<StationAir> stations = client.sido("광주");

        assertThat(stations).containsExactly(
                new StationAir("서석동", 40, 20, "2026-11-02 14:00"),
                new StationAir("농성동", 90, null, "2026-11-02 14:00"),
                new StationAir("두암동", null, 30, "2026-11-02 14:00"));
        server.verify();
    }

    @Test
    void errorCodeBecomesAirUnavailable() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr")))
                .andRespond(withSuccess(ERROR_JSON, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.sido("광주"))
                .isInstanceOf(AirUnavailableException.class)
                .hasMessageContaining("30");
    }

    @Test
    void serverErrorBecomesAirUnavailable() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.sido("광주")).isInstanceOf(AirUnavailableException.class);
    }

    @Test
    void missingKeyFailsWithoutCallingAirKorea() {
        AirKoreaClient noKey = new AirKoreaClient(builder, "");

        assertThatThrownBy(() -> noKey.sido("광주")).isInstanceOf(AirUnavailableException.class);
        server.verify();
    }
}
