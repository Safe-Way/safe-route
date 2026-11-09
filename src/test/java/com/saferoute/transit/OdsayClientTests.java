package com.saferoute.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 가짜 ODsay 서버로 요청 모양과 응답 읽기를 확인 (인터넷 없이 돈다) */
class OdsayClientTests {

    /** ODsay searchPubTransPathT 답을 줄인 것: 걷기 → 버스 → 걷기 → 지하철 → 걷기 */
    static final String PATH_JSON = """
            { "result": { "path": [ {
                "pathType": 3,
                "info": { "totalTime": 38, "payment": 1250, "totalWalk": 620 },
                "subPath": [
                  { "trafficType": 3, "distance": 250, "sectionTime": 4 },
                  { "trafficType": 2, "distance": 3100, "sectionTime": 12, "stationCount": 6,
                    "lane": [ { "busNo": "첨단09", "busID": 1 }, { "busNo": "봉선37" } ],
                    "startName": "조선대학교", "startX": "126.9301", "startY": "35.1420",
                    "endName": "문화전당역", "endX": 126.9190, "endY": 35.1470,
                    "passStopList": { "stations": [
                      { "index": 0, "stationName": "조선대학교", "x": "126.9301", "y": "35.1420" },
                      { "index": 1, "stationName": "산수오거리 ", "x": "126.9250", "y": "35.1450" },
                      { "index": 2, "stationName": "좌표없음", "x": "", "y": "" },
                      { "index": 3, "stationName": "문화전당역", "x": "126.9190", "y": "35.1470" } ] } },
                  { "trafficType": 3, "distance": 120, "sectionTime": 2 },
                  { "trafficType": 1, "distance": 5200, "sectionTime": 11, "stationCount": 5,
                    "lane": [ { "name": "광주 1호선", "subwayCode": 1 } ], "way": "녹동",
                    "startName": "문화전당", "startX": 126.9195, "startY": 35.1465,
                    "endName": "남광주", "endX": 126.9300, "endY": 35.1390 },
                  { "trafficType": 3, "distance": 250, "sectionTime": 4 }
                ] } ] } }
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void sendsLonAsXAndEncodesKey() {
        OdsayClient c = new OdsayClient(builder, "ab/c+d=", 30, clock);
        server.expect(requestTo(Matchers.allOf(
                        Matchers.startsWith(OdsayClient.URL + "?apiKey=ab%2Fc%2Bd%3D"),
                        Matchers.containsString("SX=126.930100&SY=35.142000"),
                        Matchers.containsString("EX=126.930000&EY=35.139000"))))
                .andRespond(withSuccess(PATH_JSON, MediaType.APPLICATION_JSON));

        List<OdsayPath> paths = c.search(35.142, 126.9301, 35.139, 126.93);

        server.verify();
        assertThat(paths).hasSize(1);
        OdsayPath p = paths.get(0);
        assertThat(p.totalMin()).isEqualTo(38);
        assertThat(p.fare()).isEqualTo(1250);
        assertThat(p.segs()).extracting(OdsayPath.Seg::type).containsExactly(3, 2, 3, 1, 3);
        OdsayPath.Seg bus = p.segs().get(1);
        assertThat(bus.lines()).containsExactly("첨단09", "봉선37");
        assertThat(bus.start()).isEqualTo(new OdsayPath.Place("조선대학교", 35.142, 126.9301));
        assertThat(p.segs().get(3).way()).isEqualTo("녹동");
        assertThat(p.segs().get(0).start()).isNull();
        assertThat(bus.stops()).extracting(OdsayPath.Place::name).containsExactly("조선대학교", "산수오거리", "문화전당역");
        assertThat(bus.stops().get(1)).isEqualTo(new OdsayPath.Place("산수오거리", 35.145, 126.925));
        assertThat(p.segs().get(3).stops()).isEmpty();
        assertThat(p.segs().get(0).stops()).isEmpty();
    }

    @Test
    void tooCloseAndNoRouteHaveOwnCodes() {
        assertThatThrownBy(() -> OdsayClient.toPaths(json("{\"error\":[{\"code\":\"-98\",\"message\":\"출, 도착지가 700m이내입니다.\"}]}")))
                .isInstanceOf(TransitException.class).extracting("code").isEqualTo("TOO_CLOSE");
        assertThatThrownBy(() -> OdsayClient.toPaths(json("{\"error\":{\"code\":\"-99\",\"msg\":\"검색결과가 없습니다.\"}}")))
                .isInstanceOf(TransitException.class).extracting("code").isEqualTo("NO_ROUTE");
        assertThatThrownBy(() -> OdsayClient.toPaths(json("{\"error\":[{\"code\":\"-8\",\"message\":\"ApiKeyAuthFailed\"}]}")))
                .isInstanceOf(TransitException.class).extracting("code").isEqualTo("TRANSIT_UNAVAILABLE");
    }

    @Test
    void serverErrorIsUnavailable() {
        OdsayClient c = new OdsayClient(builder, "k", 30, clock);
        server.expect(requestTo(Matchers.startsWith(OdsayClient.URL))).andRespond(withServerError());
        assertThatThrownBy(() -> c.search(35.1, 126.9, 35.2, 126.8))
                .isInstanceOf(TransitException.class).extracting("code").isEqualTo("TRANSIT_UNAVAILABLE");
    }

    @Test
    void stopsAtDailyLimit() {
        OdsayClient c = new OdsayClient(builder, "k", 2, clock);
        server.expect(ExpectedCount.times(2), requestTo(Matchers.startsWith(OdsayClient.URL)))
                .andRespond(withSuccess(PATH_JSON, MediaType.APPLICATION_JSON));
        c.search(35.1, 126.9, 35.2, 126.8);
        c.search(35.1, 126.9, 35.2, 126.8);
        assertThatThrownBy(() -> c.search(35.1, 126.9, 35.2, 126.8))
                .isInstanceOf(TransitException.class).extracting("code").isEqualTo("DAILY_LIMIT");
        server.verify();
    }

    @Test
    void missingKeyIsUnavailable() {
        OdsayClient c = new OdsayClient(builder, "", 30, clock);
        assertThatThrownBy(() -> c.search(35.1, 126.9, 35.2, 126.8))
                .isInstanceOf(TransitException.class).extracting("code").isEqualTo("TRANSIT_UNAVAILABLE");
    }

    static tools.jackson.databind.JsonNode json(String s) {
        return new tools.jackson.databind.ObjectMapper().readTree(s);
    }
}
