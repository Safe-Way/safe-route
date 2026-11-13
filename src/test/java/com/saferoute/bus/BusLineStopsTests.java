package com.saferoute.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** "수완동으로 가는 버스": 노선별 정류장으로 지나는 노선 찾기 (11-13) */
class BusLineStopsTests {

    final GjBisClient bis = mock(GjBisClient.class);
    final BusLineRepository repo = mock(BusLineRepository.class);
    final BusLineStops stops = new BusLineStops(bis, repo, false);

    static BusLine line(int id, String name, String from, String to) {
        return new BusLine(id, name, from, to, null, null, null, 35.0, 126.0);
    }

    @Test
    void passingLinesComeFirstNearestStopFirst() {
        stops.put(Map.of(
                28, List.of(new LineStop(1, 4, "첨단공영차고지", 35.2313, 126.8298),
                        new LineStop(20, 501, "수완지구", 35.1905, 126.8205)),
                40, List.of(new LineStop(1, 7, "운암동", 35.18, 126.88),
                        new LineStop(9, 502, "수완호수공원", 35.1950, 126.8230)),
                50, List.of(new LineStop(1, 8, "조선대", 35.14, 126.93))));
        when(repo.byIds(List.of(28, 40))).thenReturn(List.of(line(28, "03", "첨단", "조선대"),
                line(40, "수완49", "운암동", "광천터미널")));
        when(repo.endingAt("수완")).thenReturn(List.of(line(40, "수완49", "운암동", "광천터미널"),
                line(60, "수완12", "수완지구", "광주역")));

        List<BusLineStops.Hit> hits = stops.to("수완동", 35.1902, 126.8202);
        assertThat(hits).extracting(h -> h.line().name()).containsExactly("03", "수완49", "수완12");
        assertThat(hits.get(0).via().name()).isEqualTo("수완지구");
        assertThat(hits.get(2).via()).isNull(); // 기점·종점으로만 찾은 것
    }

    @Test
    void nameMustStartWithThePlace() {
        // "광주역"은 이름으로 볼 때 "남광주역"과 섞이면 안 됨 (좌표를 모를 때)
        stops.put(Map.of(1, List.of(new LineStop(1, 1, "남광주역", 35.13, 126.92)),
                2, List.of(new LineStop(1, 2, "광주역", 35.165, 126.909))));
        when(repo.byIds(List.of(2))).thenReturn(List.of(line(2, "18", "광주역", "남평")));
        when(repo.endingAt(anyString())).thenReturn(List.of());
        assertThat(stops.to("광주역", null, null)).extracting(h -> h.line().id()).containsExactly(2);
    }

    @Test
    void stopsTryingWhenTheFirstThreeFail() {
        when(repo.allIds()).thenReturn(List.of(1, 2, 3, 4, 5));
        when(bis.lineStops(anyInt())).thenThrow(new BusUnavailableException("키 없음", null));
        stops.reload();
        verify(bis, times(3)).lineStops(anyInt());
        assertThat(stops.loaded()).isZero();
    }

    @Test
    void reloadKeepsWhatItGot() {
        when(repo.allIds()).thenReturn(List.of(28, 29));
        when(bis.lineStops(28)).thenReturn(List.of(new LineStop(1, 4, "첨단공영차고지", 35.23, 126.83)));
        when(bis.lineStops(29)).thenThrow(new BusUnavailableException("잠깐 실패", null));
        stops.reload();
        assertThat(stops.loaded()).isEqualTo(1);
        when(repo.byIds(anyList())).thenReturn(List.of());
    }
}
