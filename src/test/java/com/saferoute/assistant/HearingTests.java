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
import com.saferoute.report.ReportService;
import com.saferoute.route.RouteService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;
import com.saferoute.walk.WalkSpeedService;

import tools.jackson.databind.json.JsonMapper;

/** 11-12 음성 인식 ②: 앱이 보낸 받아쓰기 후보(alternatives) 중 알아들을 수 있는 것 쓰기 */
class HearingTests {

    static final String A = "00000000-0000-4000-8000-00000000de14";
    static final double LAT = 35.1480;
    static final double LON = 126.9260;
    static final List<AssistantRequest.Favorite> HOME = List.of(new AssistantRequest.Favorite("집", 35.1480, 126.9100));

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final RouteService routes = mock(RouteService.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    final AssistantService service;

    HearingTests() {
        AssistantTools tools = new AssistantTools(kakao, mock(TripRecommender.class), mock(FacilityRepository.class),
                memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge(),
                new StopPlanner(kakao, routes, mock(WalkSpeedService.class)));
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain gemini = new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), gemini, tools, json,
                Clock.fixed(Instant.parse("2026-11-12T08:50:00Z"), ZoneId.of("Asia/Seoul")));
        when(kakao.search(any(), any(), any())).thenReturn(List.of());
        when(kakao.search(eq("남광주시장"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("20", "남광주시장", "광주 동구 학동", 35.1385, 126.9245, 1000)));
        when(routes.route(any())).thenAnswer(inv -> StopPlannerTests.fakeEngine(inv.getArgument(0)));
    }

    AssistantReply ask(String text, List<String> alternatives) {
        return service.answer(new AssistantRequest(A, text, LAT, LON, null, null, null, HOME, null, "adult",
                alternatives));
    }

    static AssistantRequest req(String text, String choice, List<String> alternatives) {
        return new AssistantRequest(A, text, LAT, LON, null, null, choice, HOME, null, "adult", alternatives);
    }

    @Test
    void candidatesStartWithTextAndSkipBlankOrSame() {
        assertThat(Hearing.candidates(req("가", null, java.util.Arrays.asList("나", "", null, "가", "다", "라", "마", "바"))))
                .containsExactly("가", "나", "다", "라", "마");
        assertThat(Hearing.candidates(req("가", null, null))).containsExactly("가");
        assertThat(Hearing.candidates(req("가", "adult", List.of("나")))).containsExactly("가"); // 버튼 답
        assertThat(Hearing.candidates(req("", null, List.of("나")))).containsExactly("");
    }

    @Test
    void theCandidateThatReadsAsATripIsTakenFirst() {
        assertThat(Hearing.best(List.of("다이소 갔다가 집 갈래", "다이소 같다 가 집 갈래"))).isZero(); // 같으면 앞
        assertThat(Hearing.best(List.of("다 이 소 같다 가 집 갈래", "다이소 갔다가 집 갈래"))).isEqualTo(1);
        assertThat(Hearing.score("광주역에서 다이소 갔다가 집 갈래"))
                .isGreaterThan(Hearing.score("다이소 갔다가 집 갈래"));
    }

    @Test
    void anUrgentCandidateIsNeverMissed() {
        assertThat(Hearing.best(List.of("사려 줘", "살려 줘"))).isEqualTo(1);
        AssistantReply r = ask("사려 줘", List.of("살려 줘"));
        assertThat(r.choices()).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void aPlaceNotFoundIsTriedWithTheNextCandidate() {
        AssistantReply r = ask("남광주시정 갔다가 집 갈래", List.of("남광주 시정 갔다가 집 갈래", "남광주시장 갔다가 집 갈래"));
        assertThat(r.stops()).extracting(AssistantReply.Stop::name).containsExactly("남광주시장");
        assertThat(((Map<String, Object>) r.pick().request().get("destination")).get("name")).isEqualTo("집");
        // ① 무엇으로 알아들었는지
        assertThat(r.say()).startsWith("'남광주시장 갔다가 집 갈래'라고 알아들었어요.");
        assertThat(r.heard().alternative()).isTrue();
        assertThat(r.heard().stops()).containsExactly("남광주시장");
        assertThat(r.heard().destination()).isEqualTo("집");
        assertThat(r.heard().origin()).isNull();
    }

    @Test
    void heardShowsTheCleanedWordsAndTheStart() {
        when(kakao.search(eq("광주역"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("9", "광주역", "광주 북구 무등로 235", 35.1651, 126.9093, 2100)));
        AssistantReply r = ask("어.. 잠시만.. 광주역에서 남광주시장 갓다가 집 갈께", List.of());
        assertThat(r.heard().text()).isEqualTo("광주역에서 남광주시장 갔다가 집 갈게");
        assertThat(r.heard().alternative()).isFalse();
        assertThat(r.heard().origin()).isEqualTo("광주역");
        assertThat(r.heard().stops()).containsExactly("남광주시장");
        assertThat(r.heard().destination()).isEqualTo("집");
        assertThat(r.say()).doesNotContain("알아들었어요");
    }

    @Test
    void withoutCandidatesTheOldAnswerStays() {
        AssistantReply r = ask("남광주시정 갔다가 집 갈래", List.of());
        assertThat(r.pick()).isNull();
        assertThat(r.say()).contains("남광주시정");
    }

    @Test
    void theAppJsonShapeIsRead() {
        AssistantRequest r = JsonMapper.builder().build().readValue(
                "{\"text\":\"가\",\"alternatives\":[\"나\",\"다\"]}", AssistantRequest.class);
        assertThat(r.alternatives()).containsExactly("나", "다");
        AssistantRequest old = JsonMapper.builder().build().readValue("{\"text\":\"가\"}", AssistantRequest.class);
        assertThat(old.alternatives()).isNull();
    }
}
