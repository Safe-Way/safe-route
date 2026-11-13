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
        assertThat(places).extracting(Place::name).containsExactly("조선대해오름관 정류장", "조선대병원 정류장");
        assertThat(places.get(0).address()).isEqualTo("조선대병원 방면");
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
}
