package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.saferoute.report.ReportService;
import com.saferoute.route.api.RouteResult;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.transit.TransitResult;

import tools.jackson.databind.json.JsonMapper;

/** 비 오는 날 엔진이 찾은 지붕 있는 길(covered.roof, 지하 포함)을 말로 (원일님 엔진 2026-11-09) */
class RainRoofTests {

    static final String A = "00000000-0000-4000-8000-00000000de02";
    static final WeatherDust RAIN = new WeatherDust(true, false, false, 12.0, true, false, true);
    static final WeatherDust CLEAR = new WeatherDust(false, false, false, 15.0, true, false, true);

    static RouteResult.Route route(int meters, Map<String, Integer> covered) {
        return new RouteResult.Route("safe", meters, meters / 80, List.of(), Map.of(), covered,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    static Trip walk(int roofM) {
        return new Trip("walk", 13, 1000, 0, 0, "걸어서 13분, 1,000m", Map.of(), "safe", Map.of(), List.of(), false,
                null, null, List.of(), route(1000, roofM < 0 ? Map.of() : Map.of("roof", roofM)));
    }

    static TransitResult.Leg walkLeg(int m, Integer roofM) {
        return new TransitResult.Leg("walk", m, m / 80, new TransitResult.Stop("a", 35.14, 126.92),
                new TransitResult.Stop("b", 35.15, 126.93), List.of(), null, null, true, null, null, null,
                List.of(), List.of(), List.of(), route(m, roofM == null ? Map.of() : Map.of("roof", roofM)));
    }

    @Test
    void walkSaysRoofLengthInTens() {
        assertThat(Sentences.roof(walk(604))).isEqualTo(" 이 길은 600m가 지붕 있는 길(지하 포함)이에요.");
    }

    @Test
    void noRoofOrTinyRoofSaysNothing() {
        assertThat(Sentences.roof(walk(0))).isEmpty();
        assertThat(Sentences.roof(walk(4))).isEmpty();
        assertThat(Sentences.roof(walk(-1))).isEmpty(); // 엔진이 roof 키를 안 줌 (비 기준 안 씀)
        Trip noRoute = new Trip("walk", 13, 1000, 0, 0, "걸어서 13분", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        assertThat(Sentences.roof(noRoute)).isEmpty();
    }

    @Test
    void transitAddsUpWalkLegs() {
        TransitResult.Leg bus = new TransitResult.Leg("bus", 3000, 10, new TransitResult.Stop("b", 35.15, 126.93),
                new TransitResult.Stop("c", 35.16, 126.94), List.of("59"), null, 6, null, null, null, true,
                List.of(), List.of(), List.of());
        Trip transit = new Trip("transit", 20, 600, 0, 0, "버스 59번, 20분", Map.of(), null, Map.of(), List.of(),
                false, null, null, List.of(walkLeg(400, 180), bus, walkLeg(200, 120)));
        assertThat(Sentences.roof(transit)).isEqualTo(" 걷는 길 중 300m는 지붕 있는 길(지하 포함)이에요.");
    }

    @Test
    void rainyWalkAnswerTellsTheRoofButClearDayDoesNot() {
        assertThat(answer(RAIN)).contains("지붕 있는 길(지하 포함)", "600m", "우산");
        assertThat(answer(CLEAR)).doesNotContain("지붕");
    }

    String answer(WeatherDust w) {
        KakaoLocalClient kakao = mock(KakaoLocalClient.class);
        TripRecommender trips = mock(TripRecommender.class);
        MemoryRepository memory = mock(MemoryRepository.class);
        UnmetRepository unmet = mock(UnmetRepository.class);
        AssistantTools tools = new AssistantTools(kakao, trips, mock(FacilityRepository.class), memory,
                mock(ReportService.class), mock(WeatherDustService.class), new NightJudge());
        JsonMapper json = JsonMapper.builder().build();
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        AssistantService service = new AssistantService(new RuleBrain(tools, memory, unmet),
                new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json), tools, json, clock);
        when(kakao.search(eq("국립아시아문화전당"), any(), any())).thenReturn(List.of(
                new Place("1", "국립아시아문화전당", "광주 동구", 35.1467, 126.9203, null)));
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk(600), List.of(),
                List.of()), w, false, null));
        return service.answer(new AssistantRequest(A, "문화전당 가자", 35.1480, 126.9260, null, null, null, null, null,
                "adult")).say();
    }
}
