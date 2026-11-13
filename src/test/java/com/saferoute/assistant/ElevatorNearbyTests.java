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

import com.saferoute.facility.Facility;
import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.Place;
import com.saferoute.report.ReportService;
import com.saferoute.route.RouteService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.walk.WalkSpeedService;

import tools.jackson.databind.json.JsonMapper;

/** 11-13 "근처 엘리베이터": 되묻지 않고 엘리베이터 육교·엘리베이터 출구 중 가장 가까운 곳 */
class ElevatorNearbyTests {

    static final String A = "00000000-0000-4000-8000-00000000de15";
    static final double LAT = 35.1480;
    static final double LON = 126.9260;

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final FacilityRepository facilities = mock(FacilityRepository.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    final AssistantService service;

    ElevatorNearbyTests() {
        AssistantTools tools = new AssistantTools(kakao, mock(TripRecommender.class), facilities,
                memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge(),
                new StopPlanner(kakao, mock(RouteService.class), mock(WalkSpeedService.class)));
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain gemini = new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), gemini, tools, json,
                Clock.fixed(Instant.parse("2026-11-13T00:30:00Z"), ZoneId.of("Asia/Seoul")));
        when(facilities.near(anyDouble(), anyDouble(), eq("elevator"), eq(false), any(Integer.class)))
                .thenReturn(List.of(new Facility(7, "subway_entrance", null, "문화전당역 5번 출구", null, 35.1470, 126.9200,
                        180, null, null, true, 1, List.of("wheelchair"), "출구 쪽 지상 엘리베이터")));
    }

    AssistantReply ask(String text) {
        return service.answer(new AssistantRequest(A, text, LAT, LON, null, null, null, null, null, "wheelchair"));
    }

    @Test
    void nearbyElevatorIsShownWithoutAskingWhichOne() {
        for (String said : List.of("근처 엘리베이터", "엘리베이터 어디야", "가까운 엘베 찾아줘", "가까운 엘리베이터로 가자")) {
            AssistantReply r = ask(said);
            assertThat(r.say()).as(said).contains("가장 가까운 엘리베이터는 문화전당역 5번 출구", "180m");
            assertThat(r.place()).as(said).isNotNull();
            assertThat(r.place().kind()).isEqualTo("subway_entrance");
            assertThat(r.choices()).as(said).isNullOrEmpty();
        }
    }

    @Test
    void goingSomewhereWithAnElevatorStillAsksWhichOne() {
        // 재휘 점검 6번: "엘베 있는 데로 가자"는 뜻이 둘이라 되묻기 그대로
        AssistantReply r = ask("엘베 있는 데로 가자");
        assertThat(r.choices()).extracting(c -> c.value()).containsExactly("subway", "footbridge");
    }

    @Test
    void brokenElevatorOrMapWordsAreNotANearbySearch() {
        assertThat(RuleBrain.nearbyKind("엘리베이터가고장났어어디로신고해")).isNull();
        assertThat(RuleBrain.nearbyKind("근처엘리베이터지도에보여줘")).isNull();
        assertThat(RuleBrain.nearbyKind("근처엘리베이터")).isEqualTo("elevator");
    }

    @Test
    void withoutLocationItAsksForIt() {
        AssistantReply r = service.answer(new AssistantRequest(A, "근처 엘리베이터", null, null, null, null, null, null,
                null, "wheelchair"));
        assertThat(r.say() + r.ask()).contains("위치 켜기");
    }
}
