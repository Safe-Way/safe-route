package com.saferoute.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** "수완동으로 가는 버스": 미리 받아 둔 노선별 정류장 파일로 지나는 노선 찾기 (11-13) */
class BusLineStopsTests {

    final BusLineRepository repo = mock(BusLineRepository.class);

    BusLineStops with(Map<Integer, List<LineStop>> m) {
        return new BusLineStops(repo, new BusLineStops.Loaded(m, "2026-11-14"));
    }

    static BusLine line(int id, String name, String from, String to) {
        return new BusLine(id, name, from, to, null, null, null, 35.0, 126.0);
    }

    @Test
    void passingLinesComeFirstNearestStopFirst() {
        BusLineStops stops = with(Map.of(
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
        BusLineStops stops = with(Map.of(1, List.of(new LineStop(1, 1, "남광주역", 35.13, 126.92)),
                2, List.of(new LineStop(1, 2, "광주역", 35.165, 126.909))));
        when(repo.byIds(List.of(2))).thenReturn(List.of(line(2, "18", "광주역", "남평")));
        when(repo.endingAt(anyString())).thenReturn(List.of());
        assertThat(stops.to("광주역", null, null)).extracting(h -> h.line().id()).containsExactly(2);
    }

    @Test
    void widensTo800mWhenNothingIsWithin400m() {
        // 카카오가 긴 거리 한가운데 한 점만 줄 때: 400m 안엔 없고 600m에 정류장
        BusLineStops stops = with(Map.of(7, List.of(new LineStop(3, 70, "문화전당역", 35.1460, 126.9200))));
        when(repo.byIds(List.of(7))).thenReturn(List.of(line(7, "07", "상무지구", "지원동")));
        when(repo.endingAt(anyString())).thenReturn(List.of());
        List<BusLineStops.Hit> hits = stops.to("어떤거리", 35.1460, 126.9266); // 약 600m 동쪽
        assertThat(hits).extracting(h -> h.via().name()).containsExactly("문화전당역");
        assertThat(com.saferoute.place.BusLineQuery.anchor("충장로")).isEqualTo("국립아시아문화전당");
        assertThat(com.saferoute.place.BusLineQuery.anchor("충장로 3가")).isEqualTo("국립아시아문화전당");
        assertThat(com.saferoute.place.BusLineQuery.anchor("구 도청")).isEqualTo("국립아시아문화전당");
        assertThat(com.saferoute.place.BusLineQuery.anchor("수완동")).isEqualTo("수완동");
    }

    @Test
    @SuppressWarnings("unchecked")
    void chungjangroCountsOldCityHallStop() {
        // 현장 확인 (11-13): 충장로 가는 버스는 "구 시청"·"국립아시아문화전당(구.도청)" 정류장에서 내림
        BusLineStops stops = with(Map.of(9, List.of(new LineStop(5, 90, "구 시청", 35.1530, 126.9140)),
                10, List.of(new LineStop(2, 91, "국립아시아문화전당(구.도청)", 35.1467, 126.9195))));
        when(repo.byIds(anyList())).thenAnswer(a -> ((List<Integer>) a.getArgument(0)).stream()
                .map(id -> line(id, "0" + id, "운암동", "지원동")).toList());
        when(repo.endingAt(anyString())).thenReturn(List.of());
        assertThat(stops.to("충장로", null, null)).extracting(h -> h.via().name())
                .containsExactlyInAnyOrder("구 시청", "국립아시아문화전당(구.도청)");
        assertThat(stops.to("수완동", null, null)).isEmpty();
    }

    @Test
    void readsTheFileShape() {
        String json = """
                {"fetchedAt":"2026-11-14","lines":{
                 "28":[[1,4,"첨단공영차고지",35.2313158,126.82987772],[2,2,"광주과학기술원후문",35.23211469,126.84463582],
                       [3,9,"좌표없음",null,null]]}}
                """;
        BusLineStops.Loaded l = BusLineStops.parse(JsonMapper.builder().build().readTree(json));
        assertThat(l.fetchedAt()).isEqualTo("2026-11-14");
        assertThat(l.stops().get(28)).containsExactly(new LineStop(1, 4, "첨단공영차고지", 35.2313158, 126.82987772),
                new LineStop(2, 2, "광주과학기술원후문", 35.23211469, 126.84463582));
    }

    @Test
    void noFileMeansEndsOnly() {
        BusLineStops.Loaded l = BusLineStops.read("bus/없는파일.json");
        assertThat(l.stops()).isEmpty();
        BusLineStops stops = new BusLineStops(repo, l);
        when(repo.endingAt("수완")).thenReturn(List.of(line(60, "수완12", "수완지구", "광주역")));
        when(repo.byIds(List.of())).thenReturn(List.of());
        assertThat(stops.to("수완동", null, null)).extracting(h -> h.line().name()).containsExactly("수완12");
    }
}
