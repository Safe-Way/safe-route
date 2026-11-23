package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.Place;
import com.saferoute.report.ReportItem;
import com.saferoute.report.ReportRepository;
import com.saferoute.report.ReportService;
import com.saferoute.route.RouteService;
import com.saferoute.route.api.RouteResult;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.transit.TransitService;
import com.saferoute.walk.WalkSpeedService;

import tools.jackson.databind.json.JsonMapper;

/** 차가 막은 길 신고(036 photo_label = vehicle)는 "차가 길을 막고 있다는 신고"로 말하기 (11-23) */
class VehicleReportTests {

    // 동서로 곧은 길 약 200m (경도 0.0011 ≈ 100m)
    static final List<double[]> LINE = List.of(new double[] {126.9300, 35.1400}, new double[] {126.9311, 35.1400},
            new double[] {126.9322, 35.1400});
    static final RouteResult.Event OBSTACLE = new RouteResult.Event("신고", "막는 물건", null, null, null, 100, 110,
            false, null);

    final TripRecommender rec = new TripRecommender(mock(RouteService.class), mock(TransitService.class),
            mock(WeatherDustService.class), mock(ReportRepository.class), mock(WalkSpeedService.class),
            mock(MemoryRepository.class), mock(TripScorer.class), 30);

    static RouteResult.Route route(RouteResult.Event... events) {
        return new RouteResult.Route("safe", 200, 3, LINE, Map.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(events));
    }

    static ReportItem report(String type, double lon, boolean vehicle) {
        return new ReportItem(1, type, "막는 물건", null, null, 35.1400, lon, "open", "2026-11-23T13:00", List.of(),
                vehicle);
    }

    @Test
    void vehicleObstacleGetsItsOwnName() {
        List<RouteResult.Event> out = rec.vehicleEvents(route(OBSTACLE), List.of(report("obstacle", 126.93112, true)));
        assertThat(out).singleElement().satisfies(e -> {
            assertThat(e.title()).isEqualTo(TripRecommender.VEHICLE_TITLE);
            assertThat(e.fromM()).isEqualTo(100);
        });
    }

    @Test
    void mixedOrFarOrOtherKindsStayAsTheyAre() {
        // 같은 자리에 차량 아닌 막는 물건 신고도 있으면 그대로
        assertThat(rec.vehicleEvents(route(OBSTACLE), List.of(report("obstacle", 126.93112, true),
                report("obstacle", 126.93108, false))).get(0).title()).isEqualTo("막는 물건");
        // 차량 신고가 멀면(약 100m) 그대로
        assertThat(rec.vehicleEvents(route(OBSTACLE), List.of(report("obstacle", 126.9322, true))).get(0).title())
                .isEqualTo("막는 물건");
        // 공사 신고는 바꾸지 않음
        RouteResult.Event work = new RouteResult.Event("신고", "공사", null, null, null, 100, 110, false, null);
        assertThat(rec.vehicleEvents(route(work), List.of(report("obstacle", 126.93112, true))).get(0).title())
                .isEqualTo("공사");
        assertThat(TripRecommender.pointAt(LINE, 5000)).isEqualTo(LINE.get(2));
    }

    @Test
    void assistantSaysCarBlocking() {
        KakaoLocalClient kakao = mock(KakaoLocalClient.class);
        TripRecommender trips = mock(TripRecommender.class);
        MemoryRepository memory = mock(MemoryRepository.class);
        AssistantTools tools = new AssistantTools(kakao, trips, mock(FacilityRepository.class), memory,
                mock(ReportService.class), mock(WeatherDustService.class), new NightJudge(),
                new StopPlanner(kakao, mock(RouteService.class), mock(WalkSpeedService.class)));
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain gemini = new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json);
        AssistantService service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)),
                gemini, tools, json, Clock.fixed(Instant.parse("2026-11-23T04:40:00Z"), ZoneId.of("Asia/Seoul")));
        when(kakao.search(any(), anyDouble(), anyDouble())).thenReturn(List.of());
        when(kakao.search(any(), isNull(), isNull())).thenReturn(List.of());
        when(kakao.search(eq("국립아시아문화전당"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("1", "국립아시아문화전당", "광주 동구", 35.1467, 126.9203, null)));
        RouteResult.Event car = new RouteResult.Event("신고", TripRecommender.VEHICLE_TITLE, null, null, null, 100, 110,
                false, null);
        Trip walk = new Trip("walk", 9, 600, 0, 1, "걸어서 9분, 600m", Map.of(), "safe", Map.of(), List.of(car), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = service.answer(new AssistantRequest("00000000-0000-4000-8000-00000000c4a1", "문화전당 가자",
                35.1405, 126.9310, null, null, null, null, null, "adult"));
        assertThat(r.say()).contains("가는 길에 차가 길을 막고 있다는 신고가 있어요(잠깐일 수 있어요).")
                .doesNotContain("차량 신고");
    }
}
