package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import com.saferoute.facility.Facility;
import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.Place;
import com.saferoute.report.ReportService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;

import tools.jackson.databind.json.JsonMapper;

/**
 * 비서 대화 (제미나이 키 없음 = 규칙만). 시험 질문(docs/assistant/eval_questions.json)의 장면을 가짜 DB·카카오·길찾기로.
 * 지금 = 2026-11-06(금) 12:00, 광주.
 */
class AssistantServiceTests {

    static final String A = "00000000-0000-4000-8000-00000000de01";
    static final double LAT = 35.1480;
    static final double LON = 126.9260;

    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    final TripRecommender trips = mock(TripRecommender.class);
    final FacilityRepository facilities = mock(FacilityRepository.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    final ReportService reports = mock(ReportService.class);
    final WeatherDustService weatherDust = mock(WeatherDustService.class);
    final UnmetRepository unmet = mock(UnmetRepository.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    AssistantService service;

    @BeforeEach
    void setUp() {
        AssistantTools tools = new AssistantTools(kakao, trips, facilities, memory, reports, weatherDust,
                new NightJudge());
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain gemini = new GeminiBrain(new GeminiClient(RestClient.builder(), "", "m"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, unmet), gemini, tools, json, clock);
        when(memory.layers()).thenReturn(List.of(
                new MemoryRepository.Layer("toilet:wheelchair", "장애인 화장실", List.of("휠체어 화장실")),
                new MemoryRepository.Layer("toilet", "화장실", List.of("공중화장실")),
                new MemoryRepository.Layer("crosswalk:curb_low", "보도턱 낮춘 횡단보도", List.of("턱 없는 횡단보도")),
                new MemoryRepository.Layer("footbridge:elevator", "엘리베이터 육교", List.of("승강기 육교", "엘베 육교")),
                new MemoryRepository.Layer("cctv", "CCTV", List.of("씨씨티비"))));
    }

    void wheelchairUser() {
        when(memory.profile(A)).thenReturn(new Profile("wheelchair", "manual_wheelchair", false, null, null,
                "wheelchair", 0, 1, true, true));
    }

    AssistantReply ask(String guest, String text, String convo) {
        return service.answer(new AssistantRequest(guest, text, LAT, LON, null, convo, null, null, null));
    }

    @Test
    void newPersonIsAskedHowTheyMove() {
        AssistantReply r = ask("00000000-0000-4000-8000-000000000999", "길 좀 찾아 줘", null);
        assertThat(r.ask()).contains("어떻게 다니세요");
        assertThat(r.tools()).containsExactly("get_my_info");
        assertThat(r.choices()).extracting(AssistantReply.Choice::label).contains("휠체어");
    }

    @Test
    void toiletForWheelchairIsAccessibleOne() {
        wheelchairUser();
        when(facilities.near(LAT, LON, "toilet", true, 1000)).thenReturn(List.of(new Facility(1, "toilet", null,
                "문화전당 장애인화장실", null, 35.1478, 126.9262, 50, "이용 가능", null, true, 1, List.of(), null)));
        AssistantReply r = ask(A, "화장실 어디야", null);
        assertThat(r.say()).contains("장애인 화장실", "50m", "지금 열려 있어요");
        assertThat(r.tools()).containsExactly("find_nearby");
        assertThat(r.place().name()).isEqualTo("문화전당 장애인화장실");
    }

    @Test
    void mapLayersAreSavedOnlyAfterYes() {
        wheelchairUser();
        AssistantReply first = ask(A, "엘베 있는 육교도 보여 주고 CCTV는 빼 줘", null);
        assertThat(first.ask()).contains("엘리베이터 육교", "CCTV", "맞으세요");
        assertThat(first.say()).doesNotContain("바꿨어요");
        verify(memory, never()).saveLayer(anyString(), anyString(), anyBoolean(), any(), any());

        AssistantReply second = ask(A, "응 그렇게 해", first.conversationId());
        assertThat(second.say()).contains("바꿨");
        assertThat(second.tools()).containsExactly("set_map_layers");
        verify(memory).saveLayer(A, "footbridge:elevator", true, null, null);
        verify(memory).saveLayer(A, "cctv", false, null, null);
    }

    @Test
    void voiceReportAsksFirstThenSends() {
        wheelchairUser();
        AssistantReply first = ask(A, "여기 공사 중이라 못 지나가", null);
        assertThat(first.ask()).contains("공사", "신고할까요");
        AssistantReply second = ask(A, "응 사진은 안 찍을래", first.conversationId());
        assertThat(second.say()).contains("신고했어요", "다시");
        verify(reports).createVoice(eq("construction"), eq("wheelchair"), anyString(), eq(LAT), eq(LON));
    }

    @Test
    void noSaveWhenUserSaysNo() {
        wheelchairUser();
        AssistantReply first = ask(A, "여기 사고 났어", null);
        assertThat(first.ask()).contains("사고", "신고할까요");
        AssistantReply second = ask(A, "아니 괜찮아", first.conversationId());
        assertThat(second.say()).contains("바꾸지 않을게요");
        verify(reports, never()).createVoice(anyString(), any(), any(), anyDouble(), anyDouble());
    }

    @Test
    void injuredAsksUntilWhenAndUnknownMeansAWeek() {
        String g = "00000000-0000-4000-8000-000000000777";
        AssistantReply first = ask(g, "다리를 다쳐서 휠체어 타고 다녀", null);
        assertThat(first.ask()).contains("언제까지");
        AssistantReply second = ask(g, "잘 모르겠어", first.conversationId());
        assertThat(second.ask()).contains("일주일", "다시 여쭤", "11월 13일", "장애인 화장실");
        AssistantReply third = ask(g, "네", second.conversationId());
        assertThat(third.tools()).containsExactly("update_my_info", "set_map_layers");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> f = ArgumentCaptor.forClass(Map.class);
        verify(memory).saveProfile(eq(g), f.capture());
        assertThat(f.getValue()).containsEntry("temp_mode", "wheelchair")
                .containsEntry("temp_until", LocalDate.of(2026, 11, 13));
        verify(memory).saveLayer(g, "toilet:wheelchair", true, LocalDate.of(2026, 11, 13), "다리 다침");
    }

    @Test
    void layersUntilEndOfMonth() {
        AssistantReply r = ask(A, "장애인 화장실이랑 보도턱 낮춘 횡단보도 보여 줘 이번 달까지", null);
        assertThat(r.ask()).contains("장애인 화장실", "보도턱", "11월 30일", "맞으세요");
    }

    @Test
    void urgentWordsAskAbout112First() {
        wheelchairUser();
        AssistantReply r = ask(A, "넘어졌어 도와줘", null);
        assertThat(r.ask()).contains("112", "보호자");
        assertThat(r.choices()).extracting(AssistantReply.Choice::value).contains("call_112", "call_guardian");
        assertThat(r.tools()).isEmpty();
    }

    @Test
    void outsideGwangjuIsNotGuided() {
        wheelchairUser();
        when(kakao.search(eq("서울역"), any(), any())).thenReturn(List.of(
                new Place("1", "서울역", "서울 용산구", 37.5547, 126.9707, null)));
        AssistantReply r = ask(A, "서울역 가자", null);
        assertThat(r.say()).contains("광주").doesNotContain("분 걸려요");
        assertThat(r.pick()).isNull();
    }

    @Test
    void ambiguousElevatorAsksWhich() {
        wheelchairUser();
        AssistantReply r = ask(A, "엘베 있는 데로 가자", null);
        assertThat(r.ask()).contains("지하철", "육교", "?");
        assertThat(r.tools()).isEmpty();
    }

    @Test
    void rainyDayRecommendsLowFloorBusWithHabit() {
        wheelchairUser();
        when(kakao.search(eq("문화전당"), any(), any())).thenReturn(List.of(
                new Place("1", "국립아시아문화전당", "광주 동구", 35.1467, 126.9203, null)));
        Trip bus = new Trip("transit", 14, 120, 0, 0, "버스 30번, 14분", Map.of(), null, Map.of(), List.of(), true,
                "30번 5분 뒤");
        Trip walk = new Trip("walk", 22, 1500, 0, 0, "걸어서 22분, 1,500m", Map.of(), "safe", Map.of(), List.of(),
                false, null);
        WeatherDust rain = new WeatherDust(true, false, false, 12.0, true, false, true);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(bus, List.of(walk),
                List.of("비가 와서 덜 걷는 길", "지난번 비 올 때도 대중교통을 타셨어요", "곧 오는 저상버스가 있어요")), rain, false,
                null));
        AssistantReply r = ask(A, "문화전당 가야 해", null);
        assertThat(r.say()).contains("버스", "비", "저상", "지난번", "30번 5분 뒤");
        assertThat(r.tools()).containsExactly("search_place", "recommend_trip");
        assertThat(r.pick().type()).isEqualTo("transit");
        assertThat(r.situation().rain()).isTrue();
    }

    @Test
    void wheelchairWalkMentionsCurbs() {
        wheelchairUser();
        when(kakao.search(eq("문화전당"), any(), any())).thenReturn(List.of(
                new Place("1", "국립아시아문화전당", "광주 동구", 35.1467, 126.9203, null)));
        Trip walk = new Trip("walk", 9, 600, 0, 0, "걸어서 9분, 600m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = ask(A, "문화전당 가자", null);
        assertThat(r.say()).contains("걸어서", "턱", "휠체어").doesNotContain("계단");
    }

    @Test
    void morningSuggestionUsesHabit() {
        wheelchairUser();
        when(memory.habits(eq(A), anyInt(), any(), any())).thenReturn(List.of(
                new MemoryRepository.Habit("국립아시아문화전당", 35.147, 126.920, LocalTime.of(8, 30), 3, 0, 3)));
        Trip walk = new Trip("walk", 16, 750, 0, 0, "걸어서 16분, 750m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = service.answer(new AssistantRequest(A, "(오늘 제안)", LAT, LON, null, null, null, null,
                new AssistantRequest.Test("clear", "월 08:20", null)));
        assertThat(r.say()).contains("8시 반", "문화전당");
        assertThat(r.tools()).containsExactly("get_my_info", "recommend_trip");
    }

    @Test
    void favoriteHomeFromPhone() {
        wheelchairUser();
        Trip walk = new Trip("walk", 12, 900, 0, 0, "걸어서 12분, 900m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = service.answer(new AssistantRequest(A, "집에 가자", LAT, LON, null, null, null,
                List.of(new AssistantRequest.Favorite("집", 35.15, 126.91)), null));
        assertThat(r.say()).contains("집").doesNotContain("주소를 알려");
        verify(kakao, never()).search(anyString(), any(), any());
    }

    @Test
    void pharmacyUsesPlaceSearch() {
        wheelchairUser();
        when(kakao.search(eq("약국"), any(), any())).thenReturn(List.of(
                new Place("2", "온누리약국", "광주 동구", 35.149, 126.927, 420),
                new Place("1", "충장약국", "광주 동구", 35.148, 126.926, 300)));
        AssistantReply first = ask(A, "약국 어디야", null);
        assertThat(first.say()).contains("약국", "충장약국", "300m").doesNotContain("못 해요");
        assertThat(first.tools()).containsExactly("search_place");
        Trip walk = new Trip("walk", 5, 300, 0, 0, "걸어서 5분, 300m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply second = ask(A, "응", first.conversationId());
        assertThat(second.say()).contains("충장약국까지 걸어서 5분");
    }

    @Test
    void taxiIsNotDoneAndIsCounted() {
        wheelchairUser();
        AssistantReply r = ask(A, "택시 불러 줘", null);
        assertThat(r.say()).contains("못 해요", "버스").doesNotContain("불렀어요");
        verify(unmet).add(LocalDate.of(2026, 11, 6), "wheelchair", "택시 불러 줘", "no_tool", "택시",
                "가까운 버스나 걸어가는 길");
    }

    @Test
    void offTopicGoesBackToRoutes() {
        wheelchairUser();
        AssistantReply r = ask(A, "오늘 주식 어때", null);
        assertThat(r.say()).contains("길 안내").doesNotContain("주가");
        verify(unmet).add(any(), eq("wheelchair"), eq("오늘 주식 어때"), eq("off_topic"), eq("주식"), any());
    }

    @Test
    void thanksIsJustAnswered() {
        wheelchairUser();
        AssistantReply r = ask(A, "고마워", null);
        assertThat(r.say()).doesNotContain("신고했어요", "바꿨어요");
        assertThat(r.tools()).isEmpty();
    }

    @Test
    void fallAsksFirst() {
        AssistantReply r = ask(A, "넘어졌어 일어나기가 힘들어", null);
        assertThat(r.say()).contains("112", "보호자").doesNotContain("길을 찾");
    }

    @Test
    void cleansPhoneAndNames() {
        assertThat(UnmetRepository.clean("김철수님에게 010-1234-5678 문자 보내 줘"))
                .doesNotContain("1234", "김철수").contains("(번호)", "(이름)");
    }

    @Test
    void badConversationIdStartsOver() {
        AssistantReply r = ask(null, "", "%%%이상한값");
        assertThat(r.say()).isNotBlank();
        verify(memory, never()).saveProfile(anyString(), any());
    }
}
