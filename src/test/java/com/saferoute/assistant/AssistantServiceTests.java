package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.saferoute.route.api.RouteResult;
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
        return service.answer(new AssistantRequest(guest, text, LAT, LON, null, convo, null, null, null, null));
    }

    @Test
    void newPersonIsAskedHowTheyMove() {
        AssistantReply r = ask("00000000-0000-4000-8000-000000000999", "길 좀 찾아 줘", null);
        assertThat(question(r)).contains("어떻게 다니세요");
        assertThat(r.tools()).containsExactly("get_my_info");
        assertThat(r.choices()).extracting(AssistantReply.Choice::label).contains("휠체어");
    }

    @Test
    void appModeSkipsSetupWhenServerDoesNotKnowMe() {
        String guest = "00000000-0000-4000-8000-000000000998";
        when(kakao.search(eq("문화전당"), any(), any())).thenReturn(List.of(
                new Place("1", "국립아시아문화전당", "광주 동구", 35.1467, 126.9203, null)));
        Trip walk = new Trip("walk", 12, 700, 0, 0, "걸어서 12분, 700m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = service.answer(new AssistantRequest(guest, "문화전당 가자", LAT, LON, null, null, null, null,
                null, "senior"));
        assertThat(r.say()).doesNotContain("처음 오셨네요");
        assertThat(r.tools()).contains("recommend_trip");
        verify(trips).recommend(argThat(a -> "senior".equals(a.mode())));
    }

    @Test
    void unknownAppModeStillAsksSetup() {
        AssistantReply r = service.answer(new AssistantRequest("00000000-0000-4000-8000-000000000997", "길 좀 찾아 줘",
                LAT, LON, null, null, null, null, null, "flying"));
        assertThat(question(r)).contains("어떻게 다니세요");
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
        assertThat(question(first)).contains("엘리베이터 육교", "CCTV", "맞으세요");
        assertThat(first.say()).doesNotContain("바꿨어요");
        verify(memory, never()).saveLayer(anyString(), anyString(), anyBoolean(), any(), any());

        AssistantReply second = ask(A, "응 그렇게 해", first.conversationId());
        assertThat(second.say()).contains("바꿨");
        assertThat(second.tools()).containsExactly("set_map_layers");
        verify(memory).saveLayer(A, "footbridge:elevator", true, null, null);
        verify(memory).saveLayer(A, "cctv", false, null, null);
    }

    @Test
    void reportOnTheWayIsSaidPlainly() {
        // 원일님 w11: 엔진이 못 피한 신고는 events[] kind "신고", title 종류 이름 → "가는 길에 공사 신고가 있어요."
        wheelchairUser();
        when(kakao.search(eq("문화전당"), any(), any())).thenReturn(List.of(
                new Place("1", "국립아시아문화전당", "광주 동구", 35.1467, 126.9203, null)));
        RouteResult.Event rep = new RouteResult.Event("신고", "공사", null, null, "2026-11-16T23:59", 120, 160, true,
                null);
        Trip walk = new Trip("walk", 9, 600, 0, 1, "걸어서 9분, 600m", Map.of(), "safe", Map.of(), List.of(rep), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = ask(A, "문화전당 가자", null);
        assertThat(r.say()).contains("가는 길에 공사 신고가 있어요.", "피할 길이 없어서 지나가요")
                .doesNotContain("'공사'", "신고 신고");
    }

    @Test
    void voiceReportAsksFirstThenSends() {
        wheelchairUser();
        AssistantReply first = ask(A, "여기 공사 중이라 못 지나가", null);
        assertThat(question(first)).contains("공사", "신고할까요");
        AssistantReply second = ask(A, "응 사진은 안 찍을래", first.conversationId());
        // 신고 자리 = 지금 위치(출발)라 이번 길은 피하지 않음. 엔진 60초 기억이라 1분쯤 뒤부터 (w11 뒤 사실대로)
        assertThat(second.say()).contains("신고했어요", "1분쯤 뒤", "피할 수 있으면", "조심하세요")
                .doesNotContain("피해서 다시 안내", "아직은");
        verify(reports).createVoice(eq("construction"), eq("wheelchair"), anyString(), eq(LAT), eq(LON), eq(A));
    }

    @Test
    void noSaveWhenUserSaysNo() {
        wheelchairUser();
        AssistantReply first = ask(A, "여기 사고 났어", null);
        assertThat(question(first)).contains("사고", "신고할까요");
        AssistantReply second = ask(A, "아니 괜찮아", first.conversationId());
        assertThat(second.say()).contains("바꾸지 않을게요");
        verify(reports, never()).createVoice(anyString(), any(), any(), anyDouble(), anyDouble(), any());
    }

    @Test
    void injuredAsksUntilWhenAndUnknownMeansAWeek() {
        String g = "00000000-0000-4000-8000-000000000777";
        AssistantReply first = ask(g, "다리를 다쳐서 휠체어 타고 다녀", null);
        assertThat(question(first)).contains("언제까지");
        AssistantReply second = ask(g, "잘 모르겠어", first.conversationId());
        assertThat(question(second)).contains("일주일", "다시 여쭤", "11월 13일", "장애인 화장실");
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
        assertThat(question(r)).contains("장애인 화장실", "보도턱", "11월 30일", "맞으세요");
    }

    @Test
    void urgentWordsAskAbout112First() {
        wheelchairUser();
        AssistantReply r = ask(A, "넘어졌어 도와줘", null);
        assertThat(question(r)).contains("112", "보호자");
        assertThat(r.choices()).extracting(AssistantReply.Choice::value).contains("call_112", "call_guardian");
        assertThat(r.tools()).isEmpty();
    }

    @Test
    void someoneFollowingMeIsUrgent() {
        // 사용성 점검: "누가 자꾸 쫓아오는 것 같아" → 112·보호자부터
        AssistantReply r = ask(A, "누가 자꾸 쫓아오는 것 같아", null);
        assertThat(question(r)).contains("112", "보호자");
        assertThat(r.tools()).isEmpty();
    }

    @Test
    void askingIfLocationIsSavedGetsFixedAnswer() {
        // 사용성 점검: "이 앱은 내 위치 저장해"의 답이 정확하지 않음 → 정해 둔 문장 (제미나이 안 부름)
        AssistantReply r = ask(A, "이 앱은 내 위치 저장해?", null);
        assertThat(r.say()).contains("저장하지 않아요", "100m", "한 점");
        assertThat(r.tools()).isEmpty();
        assertThat(RuleBrain.asksLocationSaved("내위치기록남아")).isTrue();
        assertThat(RuleBrain.asksLocationSaved("집위치저장해줘")).isFalse();
        assertThat(RuleBrain.asksLocationSaved("문화전당가자")).isFalse();
    }

    @Test
    void call119IsUrgent() {
        // 민수님 63개 비교: "119 불러 줘" → 112·보호자 버튼부터 (전화는 앱이 휴대폰에서)
        AssistantReply r = ask(A, "119 불러 줘", null);
        assertThat(question(r)).contains("112", "보호자");
        assertThat(r.tools()).isEmpty();
    }

    @Test
    void quietParkLooksForNearbyParkInGwangju() {
        // 민수님 63개 비교: "조용한 공원"을 카카오가 광주 밖 가게로만 찾아 "광주 밖"이라고 답함 → "공원"으로 가까운 곳
        wheelchairUser();
        when(kakao.search(eq("조용한 공원"), any(), any())).thenReturn(List.of(
                new Place("1", "조용한공원 카페", "서울 마포구", 37.55, 126.92, null)));
        when(kakao.search(eq("공원"), any(), any())).thenReturn(List.of(
                new Place("2", "광주공원", "광주 남구", 35.1440, 126.9140, 900)));
        AssistantReply r = ask(A, "조용한 공원 가고 싶어", null);
        assertThat(r.say() + question(r)).doesNotContain("광주 밖");
        assertThat(r.choices()).extracting(AssistantReply.Choice::label).anyMatch(l -> l.contains("광주공원"));
    }

    @Test
    void firstTimeUserGetsRouteFirstThenSetupQuestion() {
        // 사용성 점검: 처음 쓰는 사람도 "문화전당 가자"면 길부터 찾고 그다음 "어떻게 다니세요?"
        String guest = "00000000-0000-4000-8000-000000000996";
        when(kakao.search(eq("문화전당"), any(), any())).thenReturn(List.of(
                new Place("1", "국립아시아문화전당", "광주 동구", 35.1467, 126.9203, null)));
        Trip walk = new Trip("walk", 12, 700, 0, 0, "걸어서 12분, 700m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = service.answer(new AssistantRequest(guest, "문화전당 가자", LAT, LON, null, null, null, null,
                null, null));
        assertThat(r.tools()).contains("recommend_trip");
        assertThat(r.pick()).isNotNull();
        assertThat(question(r)).contains("어떻게 다니세요");
        assertThat(r.choices()).extracting(AssistantReply.Choice::value).contains("walk", "wheelchair");
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
        assertThat(question(r)).contains("지하철", "육교", "?");
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
        assertThat(r.say()).contains("9분 걸려요", "턱").doesNotContain("계단", "걸어서", "휠체어로");
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
                new AssistantRequest.Test("clear", "월 08:20", null), null));
        assertThat(r.say()).contains("8시 반", "문화전당");
        assertThat(r.tools()).containsExactly("get_my_info", "recommend_trip");
    }

    // ───── 비서 점검 11/06 (학교 서버 33개 중 실패한 것) ─────

    void seniorUser() {
        when(memory.profile(A)).thenReturn(new Profile("senior", null, false, null, null, "senior", 1, 1, true, true));
    }

    @Test
    void eval5StreetNameGoesToThatStreetNotShops() {
        wheelchairUser();
        when(kakao.search(eq("충장로"), any(), any())).thenReturn(List.of(
                new Place("1", "충장당구장", "광주 동구 충장로 100", 35.1490, 126.9150, 300),
                new Place("2", "노래방 충장점", "광주 동구 충장로 120", 35.1500, 126.9160, 350),
                new Place("3", "충장사진관", "광주 동구 충장로3가 12", 35.1510, 126.9170, 400)));
        Trip walk = new Trip("walk", 9, 600, 0, 0, "휠체어로 9분, 600m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = ask(A, "충장로 쪽으로 가 줘", null);
        assertThat(r.say()).startsWith("충장로까지 9분 걸려요.").doesNotContain("당구장");
        assertThat(r.tools()).containsExactly("search_place", "recommend_trip");
        ArgumentCaptor<TripRecommender.Ask> a = ArgumentCaptor.forClass(TripRecommender.Ask.class);
        verify(trips).recommend(a.capture());
        assertThat(a.getValue().dest().lat()).isCloseTo(35.15, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void eval11SeniorWalkTellsRestCount() {
        seniorUser();
        when(kakao.search(eq("남광주시장"), any(), any())).thenReturn(List.of(
                new Place("1", "남광주시장", "광주 동구 천변우로", 35.139, 126.929, null)));
        Trip walk = new Trip("walk", 15, 900, 0, 0, "걸어서 15분, 900m", Map.of(), "safe", Map.of(), List.of(), false,
                null, 3);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = ask(A, "남광주시장 가자", null);
        assertThat(r.say()).contains("가는 길에 쉴 곳(쉼터·화장실)이 3곳 있어요");
    }

    @Test
    void eval11SeniorBusStillMentionsRest() {
        seniorUser();
        when(kakao.search(eq("남광주시장"), any(), any())).thenReturn(List.of(
                new Place("1", "남광주시장", "광주 동구 천변우로", 35.139, 126.929, null)));
        Trip bus = new Trip("transit", 15, 200, 0, 0, "버스 59번, 15분", Map.of(), null, Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(bus, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = ask(A, "남광주시장 가자", null);
        assertThat(r.say()).contains("남광주시장까지 15분 걸려요. 버스 59번으로 가요.", "쉴 곳").doesNotContain("(으)");
    }

    @Test
    void eval1RainyWheelchairWithoutBusSaysSo() {
        wheelchairUser();
        when(kakao.search(eq("문화전당"), any(), any())).thenReturn(List.of(
                new Place("1", "국립아시아문화전당", "광주 동구", 35.1467, 126.9203, null)));
        Trip walk = new Trip("walk", 22, 1500, 0, 0, "휠체어로 22분, 1,500m", Map.of(), "safe", Map.of(), List.of(),
                false, null);
        WeatherDust rain = new WeatherDust(true, false, false, 12.0, true, false, true);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), rain, false, null));
        AssistantReply r = ask(A, "문화전당 가야 해", null);
        assertThat(r.say()).contains("지금은 버스 길을 찾지 못해서 이 길을 알려 드려요");
    }

    @Test
    void polishFixesJosaPoliteEndingAndWheelchairWords() {
        assertThat(Sentences.polish("엘리베이터 육교을(를) 지도에 보여 드리겠어요. 걸어서 22분이에요.", "wheelchair"))
                .isEqualTo("엘리베이터 육교를 지도에 보여 드릴게요. 22분이에요.");
        assertThat(Sentences.polish("'충장로'(으)로 3곳, 지팡이(으)로, 장애인 화장실을(를), 카페이(가)", "adult"))
                .isEqualTo("'충장로'로 3곳, 지팡이로, 장애인 화장실을, 카페가");
        assertThat(Sentences.polish("걸어서 12분이에요.", "senior")).isEqualTo("걸어서 12분이에요.");
        assertThat(Sentences.polish("길을 찾아 드리릴게요.", "adult")).isEqualTo("길을 찾아 드릴게요.");
    }

    @Test
    void serverAddsWheelchairReasonWhenGeminiDropsIt() {
        AssistantReply.Pick walk = new AssistantReply.Pick("walk", null, "22분, 1.4km", 22, null);
        String why = AssistantService.WHEELCHAIR_WHY;
        assertThat(AssistantService.mustSay("문화전당까지 22분 걸려요.", "wheelchair", walk))
                .isEqualTo("문화전당까지 22분 걸려요. " + why);
        // 이미 있으면 두 번 붙이지 않음, 다른 모드·대중교통은 그대로
        assertThat(AssistantService.mustSay("22분 걸려요. " + why, "wheelchair", walk)).isEqualTo("22분 걸려요. " + why);
        // 제미나이가 "턱을 피한 길"이라고 지어 말하면 그 문장을 빼고 사실대로 (확인된 턱 0개, 11/11 현장 시험)
        assertThat(AssistantService.mustSay("22분 걸려요. 확인된 턱과 못 지나는 곳을 피한 길이에요.", "wheelchair", walk))
                .isEqualTo("22분 걸려요. " + why);
        assertThat(AssistantService.mustSay("턱을 피해서 골랐어요. 22분 걸려요.", "wheelchair", walk))
                .isEqualTo("22분 걸려요. " + why);
        assertThat(why).contains("턱").doesNotContain("계단", "피한 길", "휠체어로");
        assertThat(AssistantService.mustSay("22분 걸려요.", "senior", walk)).isEqualTo("22분 걸려요.");
        AssistantReply.Pick bus = new AssistantReply.Pick("transit", null, "버스 59번", 18, null);
        assertThat(AssistantService.mustSay("18분 걸려요.", "wheelchair", bus)).isEqualTo("18분 걸려요.");
    }

    @Test
    void favoriteHomeFromPhone() {
        wheelchairUser();
        Trip walk = new Trip("walk", 12, 900, 0, 0, "걸어서 12분, 900m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply r = service.answer(new AssistantRequest(A, "집에 가자", LAT, LON, null, null, null,
                List.of(new AssistantRequest.Favorite("집", 35.15, 126.91)), null, null));
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
        assertThat(first.choices()).extracting(AssistantReply.Choice::label)
                .containsExactly("충장약국 · 300m", "온누리약국 · 420m");
        assertThat(first.tools()).containsExactly("search_place");
        Trip walk = new Trip("walk", 5, 300, 0, 0, "걸어서 5분, 300m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply second = ask(A, "응", first.conversationId());
        assertThat(second.say()).contains("충장약국까지 5분 걸려요");
    }

    @Test
    void lunchAskWithoutKindGetsKindButtonsThenNearestThree() {
        wheelchairUser();
        AssistantReply first = ask(A, "점심 먹을 데 추천해 줘", null);
        assertThat(first.say()).doesNotContain("없어요", "못 해요");
        assertThat(first.choices()).extracting(AssistantReply.Choice::value)
                .containsExactly("국밥", "카페", "분식", "한식", "중식");
        when(kakao.search(eq("국밥"), any(), any())).thenReturn(List.of(
                new Place("1", "나주곰탕", "광주 동구", 35.150, 126.928, 620),
                new Place("2", "월계수식당", "광주 동구", 35.149, 126.927, 250),
                new Place("3", "부산국밥", "광주 동구", 35.151, 126.929, 480),
                new Place("4", "먼국밥", "광주 동구", 35.160, 126.940, 1900)));
        AssistantReply second = service.answer(new AssistantRequest(A, "", LAT, LON, null, first.conversationId(),
                "국밥", null, null, null));
        assertThat(question(second)).contains("가까운 국밥 3곳");
        assertThat(second.choices()).extracting(AssistantReply.Choice::label)
                .containsExactly("월계수식당 · 250m", "부산국밥 · 480m", "나주곰탕 · 620m");
        Trip walk = new Trip("walk", 4, 250, 0, 0, "걸어서 4분, 250m", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        when(trips.recommend(any())).thenReturn(new TripRecommender.Plan(new TripScorer.Scored(walk, List.of(),
                List.of()), WeatherDust.unknown(), false, null));
        AssistantReply third = service.answer(new AssistantRequest(A, "", LAT, LON, null, second.conversationId(),
                "0", null, null, null));
        assertThat(third.say()).contains("월계수식당까지 4분 걸려요");
    }

    @Test
    void nearCafeGoesStraightToThreeButtonsWithoutAi() {
        wheelchairUser();
        when(kakao.search(eq("카페"), any(), any())).thenReturn(List.of(
                new Place("1", "먼카페", "광주 동구", 35.150, 126.928, 700),
                new Place("2", "월계수카페", "광주 동구", 35.149, 126.927, 120)));
        AssistantReply r = ask(A, "가까운 카페", null);
        assertThat(r.ai()).isFalse();
        assertThat(r.tools()).containsExactly("search_place");
        assertThat(r.choices()).extracting(AssistantReply.Choice::label)
                .containsExactly("월계수카페 · 120m", "먼카페 · 700m");
        assertThat(r.place()).isNull();
        when(kakao.search(eq("국밥"), any(), any())).thenReturn(List.of(
                new Place("3", "부산국밥", "광주 동구", 35.151, 126.929, 480)));
        assertThat(ask(A, "국밥 먹으러 가자", null).choices()).extracting(AssistantReply.Choice::label)
                .containsExactly("부산국밥 · 480m");
    }

    @Test
    void distanceWords() {
        assertThat(AssistantTools.distanceWord(3)).isEqualTo("10m");
        assertThat(AssistantTools.distanceWord(247)).isEqualTo("250m");
        assertThat(AssistantTools.distanceWord(1234)).isEqualTo("1.2km");
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

    /** 앱이 보는 질문: say와 ask가 같으면 say 하나만 온다 (c10) */
    static String question(AssistantReply r) {
        return r.ask() != null ? r.ask() : r.say();
    }

    @Test
    void transitAnswerKeepsStepsAndLastTime() {
        // 비서 점검 1번(11/10): 제미나이가 "문화전당까지 14분 걸려요. 오늘은 비가 와서 덜 걷는 길이에요."만 말함
        List<com.saferoute.transit.TransitResult.Leg> legs = List.of(
                SentencesTests.walk(600, 9, "출발", "남광주역"),
                SentencesTests.subway("1호선", 2, 4, "남광주역", "문화전당역"),
                SentencesTests.walk(100, 1, "문화전당역", "도착"));
        String said = AssistantService.mustSayTransit("국립아시아문화전당까지 14분 걸려요. 오늘은 비가 와서 덜 걷는 길이에요.",
                legs, List.of("비가 와요", "지난번 비 올 때도 대중교통을 타셨어요"));
        assertThat(said).startsWith("국립아시아문화전당까지 14분 걸려요.")
                .contains("남광주역까지 9분 가서").contains("지하철 1호선을 타고")
                .endsWith("지난번 비 올 때도 대중교통을 타셨어요.");
        // 이미 말했으면 두 번 붙이지 않음
        String full = "남광주역까지 9분 가서, 지하철 1호선을 타고 가요. 지난번 비 올 때도 대중교통을 타셨어요.";
        assertThat(AssistantService.mustSayTransit(full, legs, List.of("지난번 비 올 때도 대중교통을 타셨어요")))
                .isEqualTo(full);
    }

    @Test
    void eveningWalkQuestionIsNotFood() {
        wheelchairUser();
        when(weatherDust.judge(anyDouble(), anyDouble()))
                .thenReturn(new WeatherDust(false, false, false, 14.0, true, false, true));
        AssistantReply r = ask(A, "오늘 저녁에 걸어도 괜찮을까", null);
        assertThat(question(r)).contains("걷기 괜찮아요", "해는", "가실 곳").doesNotContain("국밥", "어떤 걸 드시");
        assertThat(r.choices()).extracting(AssistantReply.Choice::label).doesNotContain("국밥");
    }
}
