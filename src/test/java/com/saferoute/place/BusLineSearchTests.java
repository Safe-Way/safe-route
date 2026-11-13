package com.saferoute.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.bus.BusLine;
import com.saferoute.bus.BusLineRepository;
import com.saferoute.bus.BusStopRepository;

/** 11-13 검색창 "버스 03", "03버스": 노선 정보 */
class BusLineSearchTests {

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final BusLineRepository lines = mock(BusLineRepository.class);
    final PlaceController controller = new PlaceController(kakao, mock(BusStopRepository.class));

    BusLineSearchTests() {
        controller.setBusLines(lines);
    }

    @Test
    void lineWords() {
        for (String q : List.of("버스 03", "03버스", "03번 버스", "수완03 버스", "버스 1187", "03번 버스 어디로 가?")) {
            assertThat(BusLineQuery.number(q)).as(q).isNotNull();
        }
        assertThat(BusLineQuery.number("버스 03")).isEqualTo("03");
        assertThat(BusLineQuery.number("수완03 버스")).isEqualTo("수완03");
        for (String q : List.of("서석동 3", "근처 버스", "수완동으로 가는 버스", "광주역 근처 정류장", "버스", "2번", "03번")) {
            assertThat(BusLineQuery.number(q)).as(q).isNull();
        }
    }

    @Test
    void searchBoxShowsLines() {
        when(lines.find("03")).thenReturn(List.of(
                new BusLine(103, "03", "수완지구", "조선대", LocalTime.of(5, 40), LocalTime.of(22, 20), 15, 35.19, 126.82),
                new BusLine(203, "운림03", "운림동", "광주역", null, null, null, null, null)));
        List<Place> ps = ((PlacesResponse) controller.places("03번 버스", 35.14, 126.93).getBody()).places();
        assertThat(ps).extracting(Place::name).containsExactly("03번 버스"); // 기점 좌표 모르는 노선은 뺌
        assertThat(ps.get(0).address()).isEqualTo("수완지구 ↔ 조선대 · 첫차 05:40 막차 22:20 · 15분마다");
        assertThat(ps.get(0).id()).isEqualTo("line:103");
        verifyNoInteractions(kakao);
    }

    @Test
    void goingToShowsLinesEndingThereThenThePlace() {
        assertThat(BusLineQuery.goingTo("수완동으로 가는 버스")).isEqualTo("수완동");
        assertThat(BusLineQuery.goingTo("충장로 가는 버스 알려줘")).isEqualTo("충장로");
        assertThat(BusLineQuery.goingTo("수완동 가자")).isNull();
        assertThat(BusLineQuery.stem("수완동")).isEqualTo("수완");
        when(lines.endingAt("수완")).thenReturn(List.of(
                new BusLine(103, "03", "수완지구", "조선대", null, null, 15, 35.19, 126.82)));
        when(kakao.search("수완동", 35.14, 126.93)).thenReturn(List.of(
                new Place("1", "수완동", "광주 광산구 수완동", 35.19, 126.82, 12000)));
        List<Place> ps = ((PlacesResponse) controller.places("수완동으로 가는 버스", 35.14, 126.93).getBody()).places();
        assertThat(ps).extracting(Place::name).containsExactly("03번 버스", "수완동");
        assertThat(ps.get(0).address()).isEqualTo("수완지구 ↔ 조선대 · 15분마다");
    }

    @Test
    void goingToShowsLinesPassingThere() {
        com.saferoute.bus.BusLineStops passing = mock(com.saferoute.bus.BusLineStops.class);
        controller.setLineStops(passing);
        when(kakao.search("수완동", null, null)).thenReturn(List.of(
                new Place("1", "수완동", "광주 광산구 수완동", 35.19, 126.82, null)));
        when(kakao.search("수완동", 35.14, 126.93)).thenReturn(List.of(
                new Place("1", "수완동", "광주 광산구 수완동", 35.19, 126.82, 12000)));
        BusLine l = new BusLine(28, "03", "첨단공영차고지", "조선대", null, null, null, null, null);
        when(passing.to("수완동", 35.19, 126.82)).thenReturn(List.of(new com.saferoute.bus.BusLineStops.Hit(l,
                new com.saferoute.bus.LineStop(20, 501, "수완지구", 35.191, 126.821))));
        List<Place> ps = ((PlacesResponse) controller.places("수완동으로 가는 버스", 35.14, 126.93).getBody()).places();
        assertThat(ps).extracting(Place::name).containsExactly("03번 버스", "수완동");
        assertThat(ps.get(0).address()).isEqualTo("수완지구 지나요 · 첨단공영차고지 ↔ 조선대");
        assertThat(ps.get(0).lat()).isEqualTo(35.191); // 지나는 정류장 좌표 (기점 좌표는 몰라도 됨)
    }
}
