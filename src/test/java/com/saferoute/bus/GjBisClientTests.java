package com.saferoute.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 가짜 광주버스정보 서버로 요청 모양과 응답 읽기를 확인 (인터넷 없이 돈다) */
class GjBisClientTests {

    // 11/05 실제 응답(정류장 1230)을 줄인 것. 두 번째 버스는 저상, 숫자를 글자로 준 경우
    private static final String ARRIVE_JSON = """
            {"RESPONSE":{"RESULT":{"RESULT_MSG":"정상적으로 처리되었습니다.","RESULT_CODE":"SUCCESS"},
             "ARRIVE_LIST":{"ITEM":[
              {"ARRIVE":"","LINE_ID":2,"LINE_NAME":"01","BUS_ID":"775235","CURR_STOP_ID":1468,
               "BUSSTOP_NAME":"전대동창회관","REMAIN_MIN":14,"REMAIN_STOP":10,"DIR_START":"세하동",
               "DIR_END":"무등산국립공원","LOW_BUS":"0","ARRIVE_FLAG":0,"LINE_KIND":2},
              {"LINE_NAME":"15","BUSSTOP_NAME":"조선대","REMAIN_MIN":"3","REMAIN_STOP":"1",
               "DIR_END":"광주역","LOW_BUS":"1"}
             ]}}}
            """;

    private static final String ONE_JSON = """
            {"RESPONSE":{"RESULT":{"RESULT_CODE":"SUCCESS"},
             "ARRIVE_LIST":{"ITEM":{"LINE_NAME":"07","REMAIN_MIN":5,"REMAIN_STOP":2,"DIR_END":"상무지구","LOW_BUS":"1",
              "BUSSTOP_NAME":"남광주"}}}}
            """;

    private static final String NONE_JSON = """
            {"RESPONSE":{"RESULT":{"RESULT_CODE":"SUCCESS"},"ARRIVE_LIST":{}}}
            """;

    private static final String ERROR_JSON = """
            {"RESPONSE":{"RESULT":{"RESULT_MSG":"등록되지 않은 서비스키","RESULT_CODE":"ERROR"}}}
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GjBisClient client = new GjBisClient(builder, "test-key");

    @Test
    void sendsStopIdAndReadsArrivals() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr" + GjBisClient.PATH)))
                .andExpect(queryParam("serviceKey", "test-key"))
                .andExpect(queryParam("BUSSTOP_ID", "1230"))
                .andRespond(withSuccess(ARRIVE_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.arrivals(1230)).containsExactly(
                new BusArrival("01", 14, 10, false, "무등산국립공원", "전대동창회관"),
                new BusArrival("15", 3, 1, true, "광주역", "조선대"));
        server.verify();
    }

    @Test
    void singleBusComesAsOneItem() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr")))
                .andRespond(withSuccess(ONE_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.arrivals(1)).containsExactly(new BusArrival("07", 5, 2, true, "상무지구", "남광주"));
    }

    @Test
    void noBusIsEmptyList() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr")))
                .andRespond(withSuccess(NONE_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.arrivals(1)).isEmpty();
    }

    @Test
    void errorCodeBecomesUnavailable() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr")))
                .andRespond(withSuccess(ERROR_JSON, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.arrivals(1)).isInstanceOf(BusUnavailableException.class)
                .hasMessageContaining("등록되지 않은 서비스키");
    }

    @Test
    void serverErrorBecomesUnavailable() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.arrivals(1)).isInstanceOf(BusUnavailableException.class);
    }

    @Test
    void missingKeyFailsWithoutCalling() {
        GjBisClient noKey = new GjBisClient(builder, "");

        assertThatThrownBy(() -> noKey.arrivals(1)).isInstanceOf(BusUnavailableException.class);
        server.verify();
    }
}
