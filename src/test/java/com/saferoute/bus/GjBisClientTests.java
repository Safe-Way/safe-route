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
              {"LINE_ID":"15","LINE_NAME":"15","BUSSTOP_NAME":"조선대","REMAIN_MIN":"3","REMAIN_STOP":"1",
               "DIR_END":"광주역","LOW_BUS":"1"}
             ]}}}
            """;

    private static final String ONE_JSON = """
            {"RESPONSE":{"RESULT":{"RESULT_CODE":"SUCCESS"},
             "ARRIVE_LIST":{"ITEM":{"LINE_ID":7,"LINE_NAME":"07","REMAIN_MIN":5,"REMAIN_STOP":2,"DIR_END":"상무지구","LOW_BUS":"1",
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
                new BusArrival(2, "01", 14, 10, false, "무등산국립공원", "전대동창회관"),
                new BusArrival(15, "15", 3, 1, true, "광주역", "조선대"));
        server.verify();
    }

    @Test
    void singleBusComesAsOneItem() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr")))
                .andRespond(withSuccess(ONE_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.arrivals(1)).containsExactly(new BusArrival(7, "07", 5, 2, true, "상무지구", "남광주"));
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

    // 11/05 실제 응답(노선 2)을 줄인 것. 마지막 버스는 정류장 0(차고지)이라 빠져야 한다
    private static final String LOCATION_JSON = """
            {"RESPONSE":{"RESULT":{"RESULT_MSG":"정상적으로 처리되었습니다.","RESULT_CODE":"SUCCESS"},
             "BUSLOCATION_LIST":{"ITEM":[
              {"NUM":1,"LINE_ID":2,"BUS_ID":"775240","CURR_STOP_ID":852,"CARNO":"광주77바5240","LOW_BUS":"0","CARDY":"0","SEQ":17},
              {"NUM":2,"LINE_ID":2,"BUS_ID":"775293","CURR_STOP_ID":1205,"CARNO":"광주77바5293","LOW_BUS":"1","CARDY":"1","SEQ":30},
              {"NUM":9,"LINE_ID":2,"BUS_ID":"775244","CURR_STOP_ID":0,"CARNO":"광주77바5244","LOW_BUS":"0","CARDY":"0","SEQ":232}
             ]},"ROW_COUNT":3}}
            """;

    @Test
    void sendsLineIdAndReadsLocationsWithoutGarageBus() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr" + GjBisClient.LOCATION_PATH)))
                .andExpect(queryParam("serviceKey", "test-key"))
                .andExpect(queryParam("LINE_ID", "2"))
                .andRespond(withSuccess(LOCATION_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.locations(2)).containsExactly(
                new GjBisClient.BusOnLine("775240", 852, false, 17),
                new GjBisClient.BusOnLine("775293", 1205, true, 30));
        server.verify();
    }

    @Test
    void noBusOnLineIsEmptyList() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr")))
                .andRespond(withSuccess("""
                        {"RESPONSE":{"RESULT":{"RESULT_CODE":"SUCCESS"},"BUSLOCATION_LIST":{"ITEM":[]},"ROW_COUNT":0}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.locations(1)).isEmpty();
    }

    @Test
    void locationErrorBecomesUnavailable() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr")))
                .andRespond(withSuccess(ERROR_JSON, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.locations(2)).isInstanceOf(BusUnavailableException.class);
    }

    // 11/13 실제 응답(03번, LINE_ID 28)을 줄인 것
    private static final String LINE_STOPS_JSON = """
            {"RESPONSE":{"RESULT":{"RESULT_MSG":"정상적으로 처리되었습니다.","RESULT_CODE":"SUCCESS"},
             "BUSSTOP_LIST":{"ITEM":[
              {"BUSSTOP_NUM":1,"LINE_ID":28,"LINE_NAME":"03","BUSSTOP_ID":4,"BUSSTOP_NAME":"첨단공영차고지",
               "ARS_ID":"5821","LONGITUDE":126.82987772,"LATITUDE":35.2313158,"RETURN_FLAG":2,"SEQ":1},
              {"BUSSTOP_NUM":2,"LINE_ID":28,"LINE_NAME":"03","BUSSTOP_ID":2,"BUSSTOP_NAME":"광주과학기술원후문",
               "ARS_ID":"5819","LONGITUDE":126.84463582,"LATITUDE":35.23211469,"RETURN_FLAG":1,"SEQ":3},
              {"BUSSTOP_NUM":3,"LINE_ID":28,"BUSSTOP_ID":9,"BUSSTOP_NAME":"좌표없음"}
             ]}}}
            """;

    @Test
    void readsStopsOfALine() {
        server.expect(requestTo(Matchers.startsWith("https://apis.data.go.kr" + GjBisClient.LINE_STOPS_PATH)))
                .andExpect(queryParam("LINE_ID", "28"))
                .andRespond(withSuccess(LINE_STOPS_JSON, MediaType.APPLICATION_JSON));
        assertThat(client.lineStops(28)).containsExactly(
                new LineStop(1, 4, "첨단공영차고지", 35.2313158, 126.82987772),
                new LineStop(2, 2, "광주과학기술원후문", 35.23211469, 126.84463582));
        server.verify();
    }
}
