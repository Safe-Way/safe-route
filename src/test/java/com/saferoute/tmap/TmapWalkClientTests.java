package com.saferoute.tmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 가짜 TMAP 서버로 요청 모양과 응답 바꾸기를 확인 (인터넷 없이 돈다, 하루 호출 수도 안 씀) */
class TmapWalkClientTests {

    // TMAP 응답 모양을 줄인 것: 점(출발) → 선 → 점(갈림길) → 선 → 점(도착)
    private static final String TMAP_JSON = """
            { "type": "FeatureCollection", "features": [
              { "geometry": { "type": "Point", "coordinates": [126.9336, 35.1401] },
                "properties": { "totalDistance": 420, "totalTime": 310 } },
              { "geometry": { "type": "LineString",
                  "coordinates": [[126.9336, 35.1401], [126.9340, 35.1405]] },
                "properties": { "distance": 60 } },
              { "geometry": { "type": "Point", "coordinates": [126.9340, 35.1405] },
                "properties": {} },
              { "geometry": { "type": "LineString",
                  "coordinates": [[126.9340, 35.1405], [126.9350, 35.1420]] },
                "properties": { "distance": 360 } },
              { "geometry": { "type": "Point", "coordinates": [126.9350, 35.1420] },
                "properties": {} }
            ] }
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer tmap = MockRestServiceServer.bindTo(builder).build();
    private final TmapWalkClient client = new TmapWalkClient(builder, "test-key");

    @Test
    void sendsKeyAndCoordinatesAndReadsRoute() {
        tmap.expect(requestTo(Matchers.startsWith("https://apis.openapi.sk.com/tmap/routes/pedestrian")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("appKey", "test-key"))
                // X 가 경도, Y 가 위도
                .andExpect(jsonPath("$.startX").value(126.9336))
                .andExpect(jsonPath("$.startY").value(35.1401))
                .andExpect(jsonPath("$.endX").value(126.9350))
                .andExpect(jsonPath("$.endY").value(35.1420))
                .andRespond(withSuccess(TMAP_JSON, MediaType.APPLICATION_JSON));

        WalkRoute route = client.walk(35.1401, 126.9336, 35.1420, 126.9350);

        assertThat(route.distanceM()).isEqualTo(420);
        assertThat(route.durationS()).isEqualTo(310);
        // 겹치는 갈림길 점은 한 번만, 위도·경도 순서로
        assertThat(route.path()).containsExactly(
                new WalkRoute.Coord(35.1401, 126.9336),
                new WalkRoute.Coord(35.1405, 126.9340),
                new WalkRoute.Coord(35.1420, 126.9350));
        tmap.verify();
    }

    @Test
    void tmapErrorBecomesTmapUnavailable() {
        tmap.expect(requestTo(Matchers.startsWith("https://apis.openapi.sk.com/")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.walk(35.1401, 126.9336, 35.1420, 126.9350))
                .isInstanceOf(TmapUnavailableException.class);
    }

    @Test
    void emptyResultBecomesTmapUnavailable() {
        tmap.expect(requestTo(Matchers.startsWith("https://apis.openapi.sk.com/")))
                .andRespond(withSuccess("{ \"features\": [] }", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.walk(35.1401, 126.9336, 35.1420, 126.9350))
                .isInstanceOf(TmapUnavailableException.class);
    }

    @Test
    void missingKeyFailsWithoutCallingTmap() {
        TmapWalkClient noKey = new TmapWalkClient(builder, "");

        assertThatThrownBy(() -> noKey.walk(35.1401, 126.9336, 35.1420, 126.9350))
                .isInstanceOf(TmapUnavailableException.class);
        tmap.verify();
    }
}
