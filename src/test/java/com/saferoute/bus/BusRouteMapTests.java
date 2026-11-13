package com.saferoute.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.common.ApiErrorHandler;
import com.saferoute.place.BusLineQuery;

/** "03번 노선도 보여줘" (11-13): 기점 → 종점 쪽 정류장만, 지도용 창구 */
class BusRouteMapTests {

    final BusLineRepository repo = mock(BusLineRepository.class);
    final BusLine l03 = new BusLine(28, "03", "조선대해오름관", "첨단공영차고지", null, null, null, 35.14, 126.93);

    /** 조선대해오름관 → 정류장1..8 → 첨단공영차고지 → 돌아오는 길 2곳 */
    static List<LineStop> roundTrip() {
        List<LineStop> s = new ArrayList<>();
        s.add(new LineStop(1, 100, "조선대해오름관", 35.140, 126.930));
        for (int i = 1; i <= 8; i++) {
            s.add(new LineStop(i + 1, 100 + i, "정류장" + i, 35.14 + i * 0.01, 126.93 - i * 0.01));
        }
        s.add(new LineStop(10, 200, "첨단공영차고지", 35.23, 126.84));
        s.add(new LineStop(11, 201, "돌아오는1", 35.22, 126.85));
        s.add(new LineStop(12, 202, "돌아오는2", 35.21, 126.86));
        return s;
    }

    BusLineStops stops() {
        return new BusLineStops(repo, new BusLineStops.Loaded(Map.of(28, roundTrip()), "2026-11-14"));
    }

    @Test
    void readsRouteQuestions() {
        assertThat(BusLineQuery.routeMap("03번 노선도 보여줘")).isEqualTo("03");
        assertThat(BusLineQuery.routeMap("03번 버스 어디어디 지나가?")).isEqualTo("03");
        assertThat(BusLineQuery.routeMap("버스 03 노선도")).isEqualTo("03");
        assertThat(BusLineQuery.routeMap("수완03 지나는 정류장 알려줘")).isEqualTo("수완03");
        assertThat(BusLineQuery.routeMap("03번 버스 어디로 가?")).isNull(); // 기점·종점만 (busLineInfo)
        assertThat(BusLineQuery.routeMap("수완지구 가는 버스")).isNull();
        assertThat(BusLineQuery.routeMap("노선도 보여줘")).isNull();
    }

    @Test
    void routeStopsAtTheLastStopNotTheWayBack() {
        List<LineStop> r = stops().route(28, "첨단공영차고지");
        assertThat(r).hasSize(10);
        assertThat(r.get(9).name()).isEqualTo("첨단공영차고지");
        assertThat(stops().route(28, "없는곳")).hasSize(12);
        assertThat(stops().route(99, "첨단공영차고지")).isEmpty();
    }

    @Test
    void endpointGivesStopsInOrder() throws Exception {
        when(repo.find("03")).thenReturn(List.of(l03));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BusLineController(repo, stops()))
                .setControllerAdvice(new ApiErrorHandler()).build();
        mvc.perform(get("/api/bus/lines/stops").param("number", "03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineId").value(28))
                .andExpect(jsonPath("$.name").value("03"))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.stops.length()").value(10))
                .andExpect(jsonPath("$.stops[0].name").value("조선대해오름관"))
                .andExpect(jsonPath("$.stops[9].lat").value(35.23));
        mvc.perform(get("/api/bus/lines/stops").param("number", "x;drop")).andExpect(status().isBadRequest());
        when(repo.find("777")).thenReturn(List.of());
        mvc.perform(get("/api/bus/lines/stops").param("number", "777")).andExpect(status().isNotFound());
    }

    @Test
    void noFileMeansNotReady() throws Exception {
        when(repo.find("03")).thenReturn(List.of(l03));
        BusLineStops empty = new BusLineStops(repo, new BusLineStops.Loaded(Map.of(), null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BusLineController(repo, empty))
                .setControllerAdvice(new ApiErrorHandler()).build();
        mvc.perform(get("/api/bus/lines/stops").param("number", "03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.stops.length()").value(0));
    }
}
