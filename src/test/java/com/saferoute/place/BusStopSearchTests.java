package com.saferoute.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;

import com.saferoute.bus.BusStop;
import com.saferoute.bus.BusStopRepository;

/** 11-13 앱 검색창 "근처 버스": 가까운 해오름관 정류장 대신 먼 정류장·'○버스 베이크샵'이 나오던 문제 */
class BusStopSearchTests {

    static final double LAT = 35.1405;
    static final double LON = 126.9310;

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final BusStopRepository stops = mock(BusStopRepository.class);
    final PlaceController controller = new PlaceController(kakao, stops);

    @Test
    void busWordsAreStopSearches() {
        for (String q : List.of("근처 버스", "버스", "가까운 버스 정류장", "버스정류장", "근처 정류장", "버스 타는 곳",
                "가까운 버스 보여 줘", "버스 정류장 어디야?", "근처버스정류소")) {
            assertThat(BusStopQuery.asks(q)).as(q).isTrue();
        }
        for (String q : List.of("엠버스 베이크샵", "버스 타고 집에 가자", "30번 버스", "버스터미널", "조선대병원 버스승강장",
                "광주종합버스터미널", "택시 정류장")) {
            assertThat(BusStopQuery.asks(q)).as(q).isFalse();
        }
    }

    @Test
    void nearStopsComeFromDbNearestFirst() {
        when(stops.near(LAT, LON, 1000, 10)).thenReturn(List.of(
                new BusStop(4001, "조선대해오름관", "조선대병원", 35.1407, 126.9315, 60),
                new BusStop(4002, "조선대병원", "학동", 35.1430, 126.9290, 330)));

        ResponseEntity<?> res = controller.places("근처 버스", LAT, LON);

        List<Place> places = ((PlacesResponse) res.getBody()).places();
        assertThat(places).extracting(Place::name).containsExactly("조선대해오름관", "조선대병원");
        assertThat(places.get(0).address()).isEqualTo("버스 정류장 · 조선대병원 방면");
        assertThat(places.get(0).distanceM()).isEqualTo(60);
        assertThat(places.get(0).id()).isEqualTo("bus:4001");
        verifyNoInteractions(kakao);
    }

    @Test
    void otherWordsStillUseKakao() {
        when(kakao.search("엠버스 베이크샵", LAT, LON)).thenReturn(List.of());
        controller.places("엠버스 베이크샵", LAT, LON);
        verify(kakao).search("엠버스 베이크샵", LAT, LON);
        verifyNoInteractions(stops);
    }

    @Test
    void dbTroubleOrNoStopFallsBackToKakao() {
        when(stops.near(anyDouble(), anyDouble(), anyInt(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        when(kakao.search(any(), any(), any())).thenReturn(List.of());
        assertThat(controller.places("근처 버스", LAT, LON).getStatusCode().value()).isEqualTo(200);
        verify(kakao).search("근처 버스", LAT, LON);
    }

    @Test
    void withoutLocationUsesKakao() {
        when(kakao.search(any(), any(), any())).thenReturn(List.of());
        controller.places("버스 정류장", null, null);
        verifyNoInteractions(stops);
    }

    @Test
    void stopsNearANamedPlaceInGwangju() {
        // 11-13: "광주역 근처 정류장"을 통째로 카카오에 넘기면 경기 광주(경기광주역)가 나왔음
        assertThat(BusStopQuery.anchor("광주역 근처 정류장")).isEqualTo("광주역");
        assertThat(BusStopQuery.anchor("송정역 근처 버스 정류장 어디야")).isEqualTo("송정역");
        assertThat(BusStopQuery.anchor("문화전당 앞 버스")).isEqualTo("문화전당");
        assertThat(BusStopQuery.anchor("근처 정류장")).isNull();
        assertThat(BusStopQuery.anchor("내 근처 정류장")).isNull();
        assertThat(BusStopQuery.anchor("광주역 근처 정류장 가는 길 알려줘 버스타고")).isNull();
        when(kakao.search("광주역", null, null)).thenReturn(List.of(
                new Place("1", "경기광주역", "경기 광주시 역동", 37.40, 127.25, null),
                new Place("2", "광주역", "광주 북구 무등로 235", 35.1652, 126.9094, null)));
        when(stops.near(35.1652, 126.9094, 1000, 10)).thenReturn(List.of(
                new BusStop(5001, "광주역", "신안사거리", 35.1648, 126.9090, 50)));
        List<Place> places = ((PlacesResponse) controller.places("광주역 근처 정류장", LAT, LON).getBody()).places();
        assertThat(places).extracting(Place::name).containsExactly("광주역");
        assertThat(places.get(0).address()).isEqualTo("버스 정류장 · 신안사거리 방면");
    }

    @Test
    void outsideGwangjuIsDroppedWhenGwangjuHasResults() {
        when(kakao.search("송정역 맛집", LAT, LON)).thenReturn(List.of(
                new Place("1", "세븐일레븐 강서송정역점", "서울 강서구", 37.56, 126.81, 290000),
                new Place("2", "송정떡갈비", "광주 광산구", 35.138, 126.793, 13000)));
        List<Place> places = ((PlacesResponse) controller.places("송정역 맛집", LAT, LON).getBody()).places();
        assertThat(places).extracting(Place::name).containsExactly("송정떡갈비");
    }

    @Test
    void goingBusSearchesThePlace() {
        // 11-13 "수완동으로 가는 버스" → 아무것도 안 나옴. 수완동을 찾아 주고, 버스 번호는 대중교통 길찾기에서
        when(kakao.search("수완동", LAT, LON)).thenReturn(List.of(
                new Place("1", "수완동", "광주 광산구 수완동", 35.19, 126.82, 12000)));
        for (String q : List.of("수완동으로 가는 버스", "수완동 가는 버스", "수완동행 버스")) {
            List<Place> ps = ((PlacesResponse) controller.places(q, LAT, LON).getBody()).places();
            assertThat(ps).as(q).extracting(Place::name).containsExactly("수완동");
        }
        assertThat(PlaceController.dropParticle("충장로")).isEqualTo("충장로");
        assertThat(PlaceController.dropParticle("충장로로")).isEqualTo("충장로");
        assertThat(PlaceController.dropParticle("광주역까지")).isEqualTo("광주역");
        assertThat(PlaceController.dropParticle("금남로")).isEqualTo("금남로");
    }
}
