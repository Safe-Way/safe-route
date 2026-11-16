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

/** 11-12 폰 시험: "광주역에서 다이소 갔다가 집으로 갈거야"에서 출발·들를 곳·도착을 다 읽기 */
class OriginStopsTests {

    static final String A = "00000000-0000-4000-8000-00000000de12";
    static final double LAT = 35.1480;
    static final double LON = 126.9260;
    static final List<AssistantRequest.Favorite> HOME = List.of(new AssistantRequest.Favorite("집", 35.1480, 126.9100));

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final RouteService routes = mock(RouteService.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    final AssistantService service;

    OriginStopsTests() {
        AssistantTools tools = new AssistantTools(kakao, mock(TripRecommender.class), mock(FacilityRepository.class),
                memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge(),
                new StopPlanner(kakao, routes, mock(WalkSpeedService.class)));
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain gemini = new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), gemini, tools, json,
                Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"), ZoneId.of("Asia/Seoul")));
        when(kakao.search(eq("광주역"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("9", "광주역", "광주 북구 무등로 235", 35.1651, 126.9093, 2100)));
        when(kakao.search(eq("다이소"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("1", "다이소 충장로점", "광주 동구 충장로", 35.1481, 126.9150, 100)));
        when(routes.route(any())).thenAnswer(inv -> StopPlannerTests.fakeEngine(inv.getArgument(0)));
    }

    AssistantReply ask(String text) {
        return service.answer(new AssistantRequest(A, text, LAT, LON, null, null, null, HOME, null, "adult"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void startStopAndDestinationAreAllRead() {
        AssistantReply r = ask("광주역에서 다이소 갔다가 집으로 갈거야");
        assertThat(r.tools()).contains("plan_stops");
        assertThat(r.stops()).extracting(AssistantReply.Stop::name).containsExactly("다이소 충장로점");
        Map<String, Object> origin = (Map<String, Object>) r.pick().request().get("origin");
        assertThat(origin).containsEntry("name", "광주역").containsEntry("lat", 35.1651).containsEntry("lon", 126.9093);
        assertThat(((Map<String, Object>) r.pick().request().get("destination")).get("name")).isEqualTo("집");
        assertThat(r.say()).startsWith("광주역에서 출발해요.");
    }

    @SuppressWarnings("unchecked")
    @Test
    void withoutAStartTheTripStartsHere() {
        AssistantReply r = ask("다이소 갔다가 집 갈래");
        assertThat(r.stops()).extracting(AssistantReply.Stop::name).containsExactly("다이소 충장로점");
        Map<String, Object> origin = (Map<String, Object>) r.pick().request().get("origin");
        assertThat(origin).containsEntry("name", "지금 위치").containsEntry("lat", LAT);
        assertThat(r.say()).doesNotContain("출발해요");
    }

    @SuppressWarnings("unchecked")
    @Test
    void nearbyLookAlikeIsNotTakenForTheStart() {
        // 카카오 가까운 순은 남광주역이 먼저 (11-12 맥 시험), 정확도 순에는 광주역이 있음
        when(kakao.search(eq("광주역"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("8", "남광주역 광주1호선", "광주 동구 제봉로", 35.1393, 126.9230, 886),
                new Place("9", "광주역", "광주 북구 무등로 235", 35.1651, 126.9093, 2100)));
        when(kakao.search(eq("광주역"), isNull(), isNull())).thenReturn(List.of(
                new Place("9", "광주역", "광주 북구 무등로 235", 35.1651, 126.9093, null)));
        AssistantReply r = ask("광주역에서 다이소 갔다가 집으로 갈거야");
        Map<String, Object> origin = (Map<String, Object>) r.pick().request().get("origin");
        assertThat(origin).containsEntry("name", "광주역").containsEntry("lat", 35.1651);
    }

    @Test
    void unknownStartIsAskedAgain() {
        when(kakao.search(eq("없는곳"), anyDouble(), anyDouble())).thenReturn(List.of());
        AssistantReply r = ask("없는곳에서 집 가자");
        assertThat(r.say()).contains("출발하실 곳", "없는곳");
        assertThat(r.pick()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void anAddressWorksAsTheDestination() {
        // "서석동 3"은 광주 밖에도 있을 수 있어 광주 안 결과만 쓴다
        when(kakao.searchAddress("서석동 3")).thenReturn(List.of(
                new Place(null, "광주 동구 서석동 3", "광주 동구 서석동 3", 35.1455, 126.9301, null)));
        AssistantReply r = service.answer(new AssistantRequest(A, "광주역에서 다이소 갔다가 서석동 3으로 갈거야", LAT, LON,
                null, null, null, HOME, null, "adult"));
        Map<String, Object> dest = (Map<String, Object>) r.pick().request().get("destination");
        assertThat(dest).containsEntry("name", "동구 서석동 3").containsEntry("lat", 35.1455);
    }

    @SuppressWarnings("unchecked")
    @Test
    void anAddressWorksAsTheStartAndGwangjuIsAddedWhenMissing() {
        when(kakao.searchAddress("서석동 3")).thenReturn(List.of(
                new Place(null, "충북 어딘가 서석동 3", "충북 어딘가 서석동 3", 36.6, 127.4, null)));
        when(kakao.searchAddress("광주 서석동 3")).thenReturn(List.of(
                new Place(null, "광주 동구 서석동 3", "광주 동구 서석동 3", 35.1455, 126.9301, null)));
        AssistantReply r = ask("서석동 3에서 다이소 갔다가 집 갈래");
        Map<String, Object> origin = (Map<String, Object>) r.pick().request().get("origin");
        assertThat(origin).containsEntry("name", "동구 서석동 3").containsEntry("lat", 35.1455);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aDongNameAloneWorks() {
        when(kakao.searchAddress("서석동")).thenReturn(List.of(
                new Place(null, "전남광주통합특별시 동구 서석동", "전남광주통합특별시 동구 서석동", 35.1440, 126.9290, null)));
        AssistantReply r = ask("다이소 갔다가 서석동 갈래");
        Map<String, Object> dest = (Map<String, Object>) r.pick().request().get("destination");
        assertThat(dest).containsEntry("name", "동구 서석동").containsEntry("lat", 35.1440);
    }

    @Test
    void hesitationIsSkippedAndNearMeansNearTheLastStop() {
        // 음성 시험 (11-12): 뜸 들이는 말 + "갔다" + 근처 병원 = 시장 근처 병원
        when(kakao.search(eq("남광주시장"), anyDouble(), anyDouble())).thenReturn(List.of(
                new Place("20", "남광주시장", "광주 동구 학동", 35.1385, 126.9245, 1000)));
        when(kakao.search(eq("병원"), eq(35.1385), eq(126.9245))).thenReturn(List.of(
                new Place("22", "학동연합의원", "광주 동구 학동", 35.1390, 126.9250, 70),
                new Place("21", "남광주의원", "광주 동구 학동", 35.1388, 126.9248, 40)));
        AssistantReply r = ask("어.. 어디더라 잠시만.. 아 남광주시장으로 갔다 근처 병원으로 갈거야!");
        assertThat(r.choices()).extracting(AssistantReply.Choice::label)
                .first().asString().startsWith("남광주의원"); // 시장에서 가까운 순
        assertThat(r.choices()).hasSize(2);
    }

    @Test
    void fillersAreOnlyWholeWords() {
        assertThat(Fillers.clean("어.. 어디더라 잠시만.. 아 남광주시장으로 갔다 근처 병원으로 갈거야!"))
                .isEqualTo("남광주시장으로 갔다 근처 병원으로 갈거야");
        assertThat(Fillers.clean("음 그러니까 문화전당 가자")).isEqualTo("문화전당 가자");
        assertThat(Fillers.clean("아파")).isEqualTo("아파");
        assertThat(Fillers.clean("아니 저장하지 마")).isEqualTo("아니 저장하지 마");
        assertThat(Fillers.clean("음..")).isEqualTo("음..");
    }

    @Test
    void addressWordsAreRead() {
        assertThat(RuleBrain.destination("서석동 3으로 가자")).isEqualTo("서석동 3");
        assertThat(RuleBrain.destination("충장로 45-1로 가자")).isEqualTo("충장로 45-1");
        assertThat(RuleBrain.destination("광주 동구 서석동 3 가자")).isEqualTo("광주 동구 서석동 3");
        assertThat(OriginReader.split("광주 동구 서석동 3에서 문화전당 가자").origin()).isEqualTo("광주 동구 서석동 3");
        assertThat(AssistantTools.looksLikeAddress("서석동 3")).isTrue();
        assertThat(AssistantTools.looksLikeAddress("제봉로 100번길 7")).isTrue();
        assertThat(AssistantTools.looksLikeAddress("문화전당")).isFalse();
        assertThat(AssistantTools.looksLikeAddress("2호선")).isFalse();
        assertThat(AssistantTools.looksLikeDong("서석동")).isTrue();
        assertThat(AssistantTools.looksLikeDong("동구 서석동")).isTrue();
        assertThat(AssistantTools.looksLikeDong("충장로1가")).isTrue();
        assertThat(AssistantTools.looksLikeDong("문화전당")).isFalse();
        assertThat(AssistantTools.looksLikeDong("다이소")).isFalse();
        assertThat(AssistantTools.shortAddress("전남광주통합특별시 동구 서석동 3")).isEqualTo("동구 서석동 3");
    }

    // ───────── 말 읽기만 ─────────

    @Test
    void readsTheStartOnlyWhenTheRestIsATrip() {
        assertThat(OriginReader.split("광주역에서 문화전당 가자")).isEqualTo(new OriginReader.Split("광주역", "문화전당 가자"));
        assertThat(OriginReader.split("문화전당역 버스정류장에서 푸른길 가고 싶어").origin()).isEqualTo("문화전당역 버스정류장");
        assertThat(OriginReader.split("집에서 조선대 갈래").origin()).isEqualTo("집");
        assertThat(OriginReader.split("여기서 문화전당 가자")).isEqualTo(new OriginReader.Split(null, "문화전당 가자"));
        assertThat(OriginReader.split("학교에서 넘어졌어")).isNull();
        assertThat(OriginReader.split("문화전당 가자")).isNull();
    }

    @Test
    void stopBeforeEsoIsNotTheStart() {
        // 11-16 시험 38번: "…편의점에서 택배 찾고"의 편의점은 들를 곳. "다이소 들렀다가 편의점"을 출발지로 보면 다이소가 빠졌다
        assertThat(OriginReader.split("다이소 들렀다가 편의점에서 택배 찾고 집에 갈래")).isNull();
        assertThat(OriginReader.split("약국 갔다가 편의점에서 택배 찾고 집에 갈래")).isNull();
        // 맨 앞이 출발지면 그대로 (시험 42·44번)
        assertThat(OriginReader.split("광주역에서 다이소 갔다가 문화전당으로 갈거야").origin()).isEqualTo("광주역");
        assertThat(OriginReader.split("문화전당역에서 푸른길 갔다가 조선대로 갈게").origin()).isEqualTo("문화전당역");
    }

    @Test
    void wentToIsAStopOnlyWhenTheSentenceEndsWithGoing() {
        StopReader.Read r = StopReader.read("충장로에서 국립아시아문화전당 갔다가 광주역으로 갈거야".replace("충장로에서 ", ""));
        assertThat(r.stops()).extracting(Conversation.Stop::query).containsExactly("국립아시아문화전당");
        assertThat(r.dest()).isEqualTo("광주역");
        assertThat(StopReader.read("다이소 갔다가 집 갈래").dest()).isEqualTo("집");
        assertThat(StopReader.read("어제 거기 갔다가 넘어졌어")).isNull();
    }

    @Test
    void goingWordsWrittenWithoutSpaces() {
        assertThat(RuleBrain.destination("문화전당 갈거야")).isEqualTo("문화전당");
        assertThat(RuleBrain.destination("문화전당 갈께")).isEqualTo("문화전당");
    }
}
