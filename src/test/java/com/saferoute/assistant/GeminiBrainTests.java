package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.saferoute.facility.Facility;
import com.saferoute.facility.FacilityRepository;
import com.saferoute.place.KakaoLocalClient;
import com.saferoute.report.ReportService;
import com.saferoute.situation.NightJudge;
import com.saferoute.situation.WeatherDustService;

import tools.jackson.databind.json.JsonMapper;

/** 가짜 제미나이 서버로 함수 호출 흐름 (인터넷 없이) */
class GeminiBrainTests {

    static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent";
    static final String A = "00000000-0000-4000-8000-00000000de01";

    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final FacilityRepository facilities = mock(FacilityRepository.class);
    final MemoryRepository memory = mock(MemoryRepository.class);
    final KakaoLocalClient kakao = mock(KakaoLocalClient.class);
    AssistantService service;

    @BeforeEach
    void setUp() {
        AssistantTools tools = new AssistantTools(kakao, mock(TripRecommender.class),
                facilities, memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge());
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain brain = new GeminiBrain(new GeminiClient(builder, "test-key", "gemini-test"), tools, json);
        service = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), brain, tools, json,
                Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneId.of("Asia/Seoul")));
        when(memory.profile(A)).thenReturn(new Profile("wheelchair", "manual_wheelchair", false, null, null,
                "wheelchair", 0, 1, true, true));
        when(memory.layers()).thenReturn(List.of(
                new MemoryRepository.Layer("cctv", "CCTV", List.of("씨씨티비"))));
        when(facilities.near(35.148, 126.926, "toilet", true, 1000)).thenReturn(List.of(new Facility(1, "toilet",
                null, "문화전당 장애인화장실", null, 35.1478, 126.9262, 50, "이용 가능", null, true, 1, List.of(), null)));
    }

    AssistantReply ask(String text) {
        return service.answer(new AssistantRequest(A, text, 35.148, 126.926, null, null, null, null, null, null));
    }

    @Test
    void appQuestionGetsReferenceFromKnowledge() {
        KnowledgeRepository knowledge = mock(KnowledgeRepository.class);
        when(knowledge.find(any())).thenReturn(List.of(new KnowledgeRepository.Piece(8, "AI가 내 위치를 아나",
                "AI는 내 정확한 위치를 몰라요. 위치 좌표는 세잎지도 서버가 길을 찾을 때만 써요.", 5.2)));
        AssistantTools tools = new AssistantTools(kakao, mock(TripRecommender.class),
                facilities, memory, mock(ReportService.class), mock(WeatherDustService.class), new NightJudge());
        JsonMapper json = JsonMapper.builder().build();
        GeminiBrain brain = new GeminiBrain(new GeminiClient(builder, "test-key", "gemini-test"), tools, json, knowledge);
        AssistantService s = new AssistantService(new RuleBrain(tools, memory, mock(UnmetRepository.class)), brain, tools,
                json, Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneId.of("Asia/Seoul")));
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("[참고]")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AI가 내 위치를 아나")))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"AI는 정확한 위치를 몰라요."}]}}]}
                        """, MediaType.APPLICATION_JSON));
        AssistantReply r = s.answer(new AssistantRequest(A, "AI가 내 위치 알아?", 35.148, 126.926, null, null, null, null,
                null, null));
        server.verify();
        assertThat(r.say()).contains("위치를 몰라요");
        assertThat(new String(java.util.Base64.getUrlDecoder().decode(r.conversationId()))).doesNotContain("[참고]");
    }

    @Test
    void placeButtonsNeverAskForANumber() {
        // 사용성 점검: 버튼에 번호가 없는데 "번호를 말씀해 주세요" → 서버 문장으로 바꿈
        Turn t = new Turn(new AssistantRequest(A, "카페 가자", 35.148, 126.926, null, null, null, null, null, null),
                Conversation.start(), java.time.LocalDateTime.of(2026, 11, 10, 10, 0), false, null);
        t.convo = t.convo.withCandidates(List.of(new Conversation.Place("카페 하나", null, 35.147, 126.925, 100),
                new Conversation.Place("카페 둘", null, 35.149, 126.927, 200))).asked("place");
        Outcome o = GeminiBrain.withPlaceButtons(t, Outcome.say("카페 2곳이 있어요. 번호를 말씀해 주세요."));
        assertThat(o.say()).doesNotContain("번호").contains("아래에서 골라 주세요");
        assertThat(o.choices()).hasSize(2);
    }

    @Test
    void placeNamesAreNotListedWhenButtonsShowThem() {
        // 비서 점검 5번(11/10): "광주비엔날레, 제16회 광주비엔날레, 광주비엔날레 제문헌 중…" → 버튼이 있으니 짧게
        Turn t = new Turn(new AssistantRequest(A, "비엔날레 가자", 35.148, 126.926, null, null, null, null, null, null),
                Conversation.start(), java.time.LocalDateTime.of(2026, 11, 10, 10, 0), false, null);
        t.convo = t.convo.withCandidates(List.of(new Conversation.Place("광주비엔날레", null, 35.18, 126.89, 100),
                new Conversation.Place("제16회 광주비엔날레", null, 35.18, 126.89, 200),
                new Conversation.Place("광주비엔날레 제문헌", null, 35.18, 126.89, 300))).asked("place");
        Outcome o = GeminiBrain.withPlaceButtons(t,
                Outcome.say("광주비엔날레, 제16회 광주비엔날레, 광주비엔날레 제문헌 중 어디로 갈까요?"));
        assertThat(o.say()).isEqualTo("3곳을 찾았어요. 어디로 갈까요? 아래에서 골라 주세요.");
        assertThat(o.choices()).hasSize(3);
        // 하나만 말하면 그대로 둠
        Outcome one = GeminiBrain.withPlaceButtons(t, Outcome.say("가까운 곳은 광주비엔날레예요. 어디로 갈까요?"));
        assertThat(one.say()).startsWith("가까운 곳은");
    }

    @Test
    void promiseWithoutToolIsCaught() {
        // 사용성 점검: "화장실 찾아 드릴게요"라고 하고 도구를 안 부름
        assertThat(GeminiBrain.promiseOnly("가까운 화장실을 찾아 드릴게요.")).isTrue();
        assertThat(GeminiBrain.promiseOnly("길을 알려드릴게요!")).isTrue();
        assertThat(GeminiBrain.promiseOnly("어디로 가실지 말씀해 주시면 찾아 드릴게요?")).isFalse();
        assertThat(GeminiBrain.promiseOnly("문화전당까지 걸어서 12분이에요.")).isFalse();
    }

    @Test
    void referenceWordsAreNotSaid() {
        // 재휘님 점검(11/09): 답 첫머리에 "[참고] 내용에 따르면"이라고 그대로 말함 → 서버가 지운다
        assertThat(GeminiBrain.dropReferenceWords("[참고] 내용에 따르면, AI는 정확한 위치를 몰라요."))
                .isEqualTo("AI는 정확한 위치를 몰라요.");
        assertThat(GeminiBrain.dropReferenceWords("참고 내용에 따르면 기록은 30일 뒤 지워져요."))
                .isEqualTo("기록은 30일 뒤 지워져요.");
        assertThat(GeminiBrain.dropReferenceWords("[참고]에 따르면 휠체어 모드는 계단을 피해요."))
                .isEqualTo("휠체어 모드는 계단을 피해요.");
        assertThat(GeminiBrain.dropReferenceWords("참고 자료를 보면 AI에는 말만 보내요."))
                .isEqualTo("AI에는 말만 보내요.");
        assertThat(GeminiBrain.dropReferenceWords("화장실을 찾아볼게요.")).isEqualTo("화장실을 찾아볼게요.");
    }

    @Test
    void narrowedRulesAreSent() {
        // 화장실 → 공통 + 가까운 곳 조각만 (길 조각의 "recommend_trip 을 한 번만"이 없음)
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("[도구: 가까운 곳]")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("[도구: 길]"))))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"화장실이 가까이 있어요."}]}}]}
                        """, MediaType.APPLICATION_JSON));
        ask("화장실 어디야");
        server.verify();
    }

    @Test
    void hurtLegAsksUntilWhenWithoutGeminiThenSevenDays() {
        // 비서 점검 24·25번: 제미나이를 부르지 않고 규칙이 "언제까지?"를 묻고, "잘 모르겠어"면 일주일
        String N = "00000000-0000-4000-8000-00000000de99";
        AssistantReply first = service.answer(new AssistantRequest(N, "다리를 다쳐서 휠체어 타고 다녀", 35.148, 126.926,
                null, null, null, null, null, null));
        assertThat(first.ai()).isFalse();
        assertThat(question(first)).contains("언제까지").doesNotContain("맞으세요");
        AssistantReply second = service.answer(new AssistantRequest(N, "잘 모르겠어", 35.148, 126.926, null,
                first.conversationId(), null, null, null, null));
        assertThat(question(second)).contains("일주일", "11월 13일까지", "다시 여쭤").doesNotContain("어떻게 다니세요");
        server.verify(); // 제미나이를 한 번도 안 부름
    }

    @Test
    void geminiPicksFindNearbyThenSpeaks() {
        server.expect(requestTo(URL)).andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("35.148"))))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[
                          {"functionCall":{"name":"find_nearby","args":{"kind":"toilet"}},"thoughtSignature":"abc"}]}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("thoughtSignature")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("functionResponse")))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[
                          {"text":"가장 가까운 장애인 화장실은 문화전당 장애인화장실로 50m예요. 지금 열려 있어요."}]}}]}
                        """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("화장실 급해");
        assertThat(r.ai()).isTrue();
        assertThat(r.say()).contains("50m");
        assertThat(r.tools()).containsExactly("find_nearby");
        assertThat(r.place().name()).isEqualTo("문화전당 장애인화장실");
        server.verify();
    }

    @Test
    void foundPlacesAlwaysComeAsButtonsWithDistance() {
        when(kakao.search(org.mockito.ArgumentMatchers.eq("서점"), any(), any())).thenReturn(List.of(
                new com.saferoute.place.Place("2", "먼서점", "광주 동구", 35.151, 126.927, 410),
                new com.saferoute.place.Place("1", "월계수서점", "광주 동구", 35.149, 126.926, 250),
                new com.saferoute.place.Place("3", "새벽서점", "광주 동구", 35.150, 126.925, 1230)));
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"search_place","args":{"query":"서점"}}}]}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("월계수서점 (250m)")))
                .andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"가까운 서점은 월계수서점이에요."}]}}]}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("가까운 서점");
        assertThat(r.ai()).isTrue();
        assertThat(r.choices()).extracting(AssistantReply.Choice::label)
                .containsExactly("월계수서점 · 250m", "먼서점 · 410m", "새벽서점 · 1.2km");
        assertThat(r.choices()).extracting(AssistantReply.Choice::value).containsExactly("0", "1", "2");
        assertThat(r.choices()).isNotEmpty();
    }

    @Test
    void madeUpNumberFallsBackToRuleSentence() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"find_nearby","args":{"kind":"toilet"}}}]}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"화장실은 20m 앞이에요."}]}}]}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("화장실 급해");
        assertThat(r.ai()).isFalse();
        assertThat(r.say()).contains("50m").doesNotContain("20m");
    }

    @Test
    void savingToolOnlyAsksAndDoesNotSave() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"set_map_layers","args":{"remove":["씨씨티비"]}}}]}}]}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("방범카메라 표시 좀 치워");
        assertThat(question(r)).contains("CCTV", "맞으세요");
        // 같은 문장은 한 번만: say 에만 있고 ask 는 비움, 예/아니요 버튼은 그대로
        assertThat(r.say()).contains("CCTV", "맞으세요");
        assertThat(r.ask()).isNull();
        assertThat(r.choices()).isNotEmpty();
        assertThat(r.tools()).containsExactly("set_map_layers");
        verify(memory, never()).saveLayer(anyString(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    void tempModeQuestionAlwaysSaysWeWillAskAgain() {
        // 오늘(11/06)부터 7일 = 일주일. 제미나이가 말을 빼먹어도 서버 확인 질문에 들어감
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"update_my_info","args":{"temp_mode":"wheelchair","temp_until":"2026-11-13"}}}]}}]}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("당분간 휠체어 탈게"); // "다쳐서"가 있으면 규칙이 먼저 언제까지 물음
        assertThat(question(r)).contains("일주일 동안", "11월 13일", "그때 다시 여쭤볼게요.");
        assertThat(r.tools()).containsExactly("update_my_info");
    }

    @Test
    void geminiDownMeansRules() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        AssistantReply r = ask("화장실 어디야");
        assertThat(r.ai()).isFalse();
        assertThat(r.say()).contains("장애인 화장실", "50m");
    }

    @Test
    void busyModelAsksBackupModel() {
        String backup = URL.replace("gemini-test", GeminiClient.BACKUP_MODELS.get(0));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("{\"error\":{\"code\":503,\"message\":\"The model is overloaded.\"}}"));
        server.expect(requestTo(backup)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"find_nearby","args":{"kind":"toilet"}}}]}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(backup)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"가장 가까운 장애인 화장실은 50m 앞이에요."}]}}]}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("화장실 급해");
        assertThat(r.ai()).isTrue();
        assertThat(r.say()).contains("50m");
        server.verify();
    }

    @Test
    void readsAnswerEvenWhenTypeIsOctetStream() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"find_nearby","args":{"kind":"toilet"}}}]}}]}
                """, MediaType.APPLICATION_OCTET_STREAM));
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"가장 가까운 장애인 화장실은 50m 앞이에요."}]}}]}
                """, MediaType.APPLICATION_OCTET_STREAM));
        AssistantReply r = ask("화장실 급해");
        assertThat(r.ai()).isTrue();
    }

    /** 앱이 보는 질문: say와 ask가 같으면 say 하나만 온다 (c10) */
    static String question(AssistantReply r) {
        return r.ask() != null ? r.ask() : r.say();
    }

    @Test
    void sendsOnlyToolsThatFitTheWords() {
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"name\":\"find_nearby\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"name\":\"report_problem\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"name\":\"set_map_layers\""))))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[
                          {"functionCall":{"name":"find_nearby","args":{"kind":"toilet"}}}]}}],
                         "usageMetadata":{"promptTokenCount":900,"candidatesTokenCount":10,"totalTokenCount":910}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"문화전당 장애인화장실이 50m에 있어요."}]}}],
                 "usageMetadata":{"promptTokenCount":950,"candidatesTokenCount":20,"totalTokenCount":970}}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("화장실 어디야");
        server.verify();
        assertThat(r.ai()).isTrue();
        assertThat(r.usage()).isEqualTo(new AssistantReply.Usage(1850, 30, 2, List.of("search_place", "find_nearby"), false));
    }

    @Test
    void wrongPickAsksAgainWithAllSeven() {
        // "공사"만 보고 신고 도구만 보냈는데, 제미나이가 보내지 않은 도구(search_place)를 부름 → 7개로 다시
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"name\":\"find_nearby\""))))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[
                          {"functionCall":{"name":"search_place","args":{"query":"공사"}}}]}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"name\":\"find_nearby\"")))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"공사 소식은 따로 알려 드릴 수 있어요."}]}}]}
                        """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("공사 소식 알려 줘");
        server.verify();
        assertThat(r.tools()).isEmpty();
        assertThat(r.usage().retried()).isTrue(); // 7개로 다시 물음
        assertThat(r.usage().tools()).isEqualTo(ToolPicker.ALL);
        assertThat(r.usage().prompt()).isZero(); // 가짜 답에 usageMetadata 가 없음
    }

    @Test
    void promiseOnlyAnswerIsToldToActuallyDoIt() {
        // 좁혀 보냈는데 "찾아 드릴게요"만 → 7개로 다시 → 또 말만 → "실제로 하세요"를 붙여 한 번 더
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"화장실을 찾아 드릴게요."}]}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"name\":\"update_my_info\"")))
                .andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"화장실을 찾아 드릴게요."}]}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("하겠다고만")))
                .andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"근처에서 화장실을 못 찾았어요."}]}}]}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("화장실 어디야");
        server.verify();
        assertThat(r.say()).contains("못 찾았어요");
        assertThat(r.usage().retried()).isTrue();
    }

    @Test
    void sayingItCannotOnFirstAnswerAsksAgain() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"그건 할 수 없어요."}]}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"name\":\"update_my_info\"")))
                .andRespond(withSuccess("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"어떤 신고인지 말씀해 주시겠어요?"}]}}]}
                """, MediaType.APPLICATION_JSON));
        AssistantReply r = ask("신고 좀");
        server.verify();
        assertThat(r.say()).contains("어떤 신고");
    }

    @Test
    void allToolsTestFlagSendsSeven() {
        server.expect(requestTo(URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"name\":\"report_problem\"")))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"어디로 가세요?"}]}}]}
                        """, MediaType.APPLICATION_JSON));
        service.answer(new AssistantRequest(A, "화장실 어디야", 35.148, 126.926, null, null, null, null,
                new AssistantRequest.Test(null, null, "all_tools"), null));
        server.verify();
    }
}
