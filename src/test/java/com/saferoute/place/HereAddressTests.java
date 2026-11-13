package com.saferoute.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.saferoute.bus.BusStopRepository;

/** 11-13 "현재 위치" 주소: 검색창에 현재 위치·내 위치를 치거나 /api/places/here 를 부르면 지금 있는 곳 주소 */
class HereAddressTests {

    static final double LAT = 35.1401;
    static final double LON = 126.9336;

    @Test
    void kakaoCoordToAddressIsRead() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.startsWith("https://dapi.kakao.com/v2/local/geo/coord2address.json")))
                .andExpect(queryParam("x", "126.9336"))
                .andExpect(queryParam("y", "35.1401"))
                .andRespond(withSuccess("""
                        { "documents": [ {
                          "road_address": { "address_name": "광주 동구 필문대로 309", "building_name": "조선대학교 본관" },
                          "address": { "address_name": "광주 동구 서석동 375" } } ] }
                        """, MediaType.APPLICATION_JSON));
        Place p = new KakaoLocalClient(builder, "k").addressAt(LAT, LON);
        assertThat(p.name()).isEqualTo("조선대학교 본관");
        assertThat(p.address()).isEqualTo("광주 동구 필문대로 309");
    }

    @Test
    void noRoadAddressUsesJibun() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.startsWith("https://dapi.kakao.com/v2/local/geo/coord2address.json")))
                .andRespond(withSuccess("""
                        { "documents": [ { "road_address": null, "address": { "address_name": "광주 동구 학동 588" } } ] }
                        """, MediaType.APPLICATION_JSON));
        Place p = new KakaoLocalClient(builder, "k").addressAt(LAT, LON);
        assertThat(p.name()).isEqualTo("광주 동구 학동 588");
        assertThat(p.address()).isEqualTo("광주 동구 학동 588");
    }

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final BusStopRepository stops = mock(BusStopRepository.class);
    final PlaceController controller = new PlaceController(kakao, stops);

    @Test
    void searchBoxHereWordsGiveMyAddress() {
        when(kakao.addressAt(LAT, LON)).thenReturn(
                new Place(null, "조선대학교 본관", "광주 동구 필문대로 309", LAT, LON, 0));
        for (String q : List.of("현재 위치", "내 위치", "현위치", "여기", "지금 있는 곳")) {
            ResponseEntity<?> res = controller.places(q, LAT, LON);
            List<Place> ps = ((PlacesResponse) res.getBody()).places();
            assertThat(ps).as(q).hasSize(1);
            assertThat(ps.get(0).name()).isEqualTo("현재 위치");
            assertThat(ps.get(0).address()).isEqualTo("광주 동구 필문대로 309 (조선대학교 본관)");
            assertThat(ps.get(0).lat()).isEqualTo(LAT);
        }
        verifyNoInteractions(stops);
    }

    @Test
    void hereEndpoint() {
        when(kakao.addressAt(LAT, LON)).thenReturn(
                new Place(null, "조선대학교 본관", "광주 동구 필문대로 309", LAT, LON, 0));
        assertThat(((Place) controller.here(LAT, LON).getBody()).address()).isEqualTo("광주 동구 필문대로 309");
        when(kakao.addressAt(1, 1)).thenReturn(null);
        assertThat(controller.here(1, 1).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void otherWordsAreNotHere() {
        assertThat(HereQuery.asks("여기서 조선대")).isFalse();
        assertThat(HereQuery.asks("위치")).isFalse();
    }
}
