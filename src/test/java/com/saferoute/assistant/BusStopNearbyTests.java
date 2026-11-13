package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.saferoute.bus.BusStop;
import com.saferoute.bus.BusStopRepository;
import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.report.ReportService;
import com.saferoute.route.RouteService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.walk.WalkSpeedService;

import tools.jackson.databind.json.JsonMapper;

/** 11-13 비서 "근처 버스": 카카오가 아니라 DB 정류장 표에서 가장 가까운 정류장 */
class BusStopNearbyTests {

    static final String A = "00000000-0000-4000-8000-00000000de17";
    static final double LAT = 35.1405;
    static final double LON = 126.9310;

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final BusStopRepository stops = mock(BusStopRepository.class);
    final AssistantService service;

    BusStopNearbyTests() {
        MemoryRepository memory = mock(MemoryRepository.class);
        AssistantTools tools = new AssistantTools(kakao, mock(TripRecommender.class), mock(FacilityRepository.class),
                memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge(),
                new StopPlanner(kakao, mock(RouteService.class), mock(WalkSpeedService.class)));
        tools.setBusStops(stops);
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain gemini = new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), gemini, tools, json,
                Clock.fixed(Instant.parse("2026-11-13T01:30:00Z"), ZoneId.of("Asia/Seoul")));
    }

    AssistantReply ask(String text) {
        return service.answer(new AssistantRequest(A, text, LAT, LON, null, null, null, null, null, "senior"));
    }

    @Test
    void nearestStopIsShown() {
        when(stops.near(LAT, LON, 1000, 1)).thenReturn(List.of(
                new BusStop(4001, "조선대해오름관", "조선대병원", 35.1407, 126.9315, 60)));
        for (String said : List.of("근처 버스", "가까운 버스 보여 줘", "버스 정류장 어디야")) {
            AssistantReply r = ask(said);
            assertThat(r.say()).as(said).contains("가장 가까운 버스 정류장은 조선대해오름관 정류장으로 60m예요.");
            assertThat(r.place().kind()).isEqualTo("bus_stop");
        }
    }

    @Test
    void noStopNearby() {
        when(stops.near(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of());
        AssistantReply r = ask("근처 버스");
        assertThat(r.say()).contains("1km 안에 버스 정류장이 없어요");
        assertThat(r.place()).isNull();
    }

    @Test
    void busTripIsNotAStopSearch() {
        assertThat(com.saferoute.place.BusStopQuery.asks("버스타고집에가자")).isFalse();
    }
}
