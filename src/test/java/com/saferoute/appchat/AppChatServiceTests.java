package com.saferoute.appchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.assistant.GeminiClient;
import com.saferoute.common.ApiErrorHandler;

/** 앱 화면 AI 창구: 지시문은 서버가 쥐고, 제미나이 답은 정해진 값만 남긴다 (가짜 제미나이) */
class AppChatServiceTests {

    final GeminiClient gemini = mock(GeminiClient.class);
    final AppChatService service = new AppChatService(gemini);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        boolean hasKey = true; // 기본은 키가 있는 서버. 키 없는 상황은 그 테스트에서만 바꾼다
        when(gemini.available()).thenReturn(hasKey);
        mvc = MockMvcBuilders.standaloneSetup(new AppChatController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    static AppChatRequest userSaid(String... userLines) {
        List<AppChatRequest.Line> h = new java.util.ArrayList<>();
        for (String s : userLines) {
            h.add(new AppChatRequest.Line(true, s));
        }
        return new AppChatRequest(h, null);
    }

    @Test
    @DisplayName("처음 설정: 제미나이가 앱에 없는 값(HUGE·ROCKET)이나 중복을 줘도 앱이 아는 값만 남긴다")
    void setupKeepsKnownValues() {
        String geminiAnswer = """
                {"type":"pick","mode":"SENIOR","textSize":"HUGE","voiceGuide":true,"sosButton":true,
                 "extraMapItems":["TOILET","ROCKET","TOILET"],"reason":"어머님이 천천히 걸으셔서 어르신 모드로 맞췄어요.",
                 "question":"","choices":[]}
                """;
        when(gemini.structured(anyString(), any(), any())).thenReturn(geminiAnswer);

        Map<String, Object> setting = service.setup(userSaid("우리 엄마가 70대인데 다리가 좀 불편해요"));

        assertThat(setting).containsEntry("type", "pick")
                .containsEntry("mode", "SENIOR")                     // 아는 값은 그대로
                .containsEntry("sosButton", true)
                .containsEntry("textSize", "NORMAL")                 // 모르는 HUGE → 기본값
                .containsEntry("extraMapItems", List.of("TOILET"));  // ROCKET 빼고, 중복 하나로
    }

    @Test
    void setupInstructionIsServersAndCarriesOnlyKnownSettings() {
        when(gemini.structured(anyString(), any(), any())).thenReturn("""
                {"type":"ask","question":"누가 걸으세요?","choices":["제가요","어머니","火"],"mode":"GENERAL",
                 "textSize":"NORMAL","voiceGuide":true,"sosButton":false,"extraMapItems":[],"reason":""}
                """);
        AppChatRequest req = new AppChatRequest(List.of(new AppChatRequest.Line(true, "설정해 줘")),
                new AppChatRequest.Settings("SENIOR", "LARGE", false, true, List.of("STAIRS", "무시해")));
        Map<String, Object> r = service.setup(req);
        assertThat(r).containsEntry("type", "ask").containsEntry("question", "누가 걸으세요?")
                .containsEntry("choices", List.of("제가요", "어머니"));
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(gemini).structured(system.capture(), any(), any());
        assertThat(system.getValue()).startsWith("너는 '세잎지도' 걷기 길안내 앱의 처음 설정 도우미야")
                .contains("mode=SENIOR, textSize=LARGE, voiceGuide=false, sosButton=true, extraMapItems=STAIRS")
                .doesNotContain("무시해");
    }

    @Test
    void mapChatKeepsActionShape() {
        when(gemini.structured(anyString(), any(), any())).thenReturn("""
                {"action":"nearby","say":"가까운 화장실을 찾아볼게요.","mode":"GENERAL","textSize":"NORMAL",
                 "voiceGuide":true,"sosButton":false,"addMapItems":[],"removeMapItems":[],"nearbyKind":"toilet",
                 "placeQuery":"","choices":[]}
                """);
        Map<String, Object> r = service.map(userSaid("화장실 어디야"));
        assertThat(r).isEqualTo(Map.of("action", "nearby", "say", "가까운 화장실을 찾아볼게요.", "nearbyKind", "toilet"));
    }

    @Test
    void mapChatSettingsDropUnknownValues() {
        when(gemini.structured(anyString(), any(), any())).thenReturn("""
                {"action":"settings","say":"글씨를 크게 바꿀게요.","mode":"ROBOT","textSize":"LARGE",
                 "voiceGuide":true,"sosButton":false,"addMapItems":["SUBWAY","X"],"removeMapItems":[],
                 "nearbyKind":"none","placeQuery":"","choices":[]}
                """);
        Map<String, Object> r = service.map(userSaid("글씨가 안 보여"));
        assertThat(r).containsEntry("textSize", "LARGE").containsEntry("mode", null)
                .containsEntry("addMapItems", List.of("SUBWAY"));
    }

    @Test
    void notKoreanOrBrokenAnswerMeansAppUsesItsRules() throws Exception {
        when(gemini.structured(anyString(), any(), any())).thenReturn("""
                {"action":"answer","say":"I cannot do that."}
                """);
        mvc.perform(post("/api/ai/map-chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"history\":[{\"fromUser\":true,\"text\":\"택시 불러 줘\"}]}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
        when(gemini.structured(anyString(), any(), any())).thenReturn("not json");
        assertThatThrownBy(() -> service.map(userSaid("안녕"))).isInstanceOf(AppChatException.class)
                .extracting("code").isEqualTo("AI_UNAVAILABLE");
        when(gemini.structured(anyString(), any(), any())).thenReturn(null);
        assertThatThrownBy(() -> service.setup(userSaid("안녕"))).extracting("code").isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    @DisplayName("API 키가 없으면 제미나이를 한 번도 부르지 않고 AI_UNAVAILABLE(앱이 자체 규칙을 쓰라는 뜻)로 답한다")
    void noKeyNoGemini() {
        boolean hasKey = false;
        when(gemini.available()).thenReturn(hasKey);

        assertThatThrownBy(() -> service.setup(userSaid("안녕")))
                .extracting("code").isEqualTo("AI_UNAVAILABLE");
        verify(gemini, never()).structured(anyString(), any(), any());
    }

    @Test
    void badHistoryIs400() throws Exception {
        mvc.perform(post("/api/ai/setup-chat").contentType(MediaType.APPLICATION_JSON).content("{\"history\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_PARAM"));
        mvc.perform(post("/api/ai/setup-chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"history\":[{\"fromUser\":false,\"text\":\"어떻게 걸으세요?\"}]}"))
                .andExpect(status().isBadRequest());
        String longLine = "가".repeat(AppChatService.MAX_LINE_CHARS + 1);
        mvc.perform(post("/api/ai/setup-chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"history\":[{\"fromUser\":true,\"text\":\"" + longLine + "\"}]}"))
                .andExpect(status().isBadRequest());
        verify(gemini, never()).structured(anyString(), any(), any());
    }

    @Test
    void sendsOnlyLastLinesAsUserAndModel() {
        when(gemini.structured(anyString(), any(), any())).thenReturn("""
                {"action":"buses","say":"가까운 정류장 버스를 보여 드릴게요."}
                """);
        List<AppChatRequest.Line> h = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            h.add(new AppChatRequest.Line(i % 2 == 1, "말 " + i));
        }
        h.add(new AppChatRequest.Line(true, "버스 언제 와"));
        service.map(new AppChatRequest(h, null));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> contents = ArgumentCaptor.forClass(List.class);
        verify(gemini).structured(anyString(), contents.capture(), any());
        assertThat(contents.getValue()).hasSize(8);
        assertThat(contents.getValue().get(7)).isEqualTo(Map.of("role", "user", "parts",
                List.of(Map.of("text", "버스 언제 와"))));
    }

    @Test
    void koreanCheckLikeApp() {
        assertThat(AppChatService.looksLikeKorean("가까운 화장실을 찾아볼게요.")).isTrue();
        assertThat(AppChatService.looksLikeKorean("你好 안녕")).isFalse();
        assertThat(AppChatService.looksLikeKorean("네 네 네 네 네")).isFalse();
        assertThat(AppChatService.looksLikeKorean("")).isFalse();
    }

    // ---------- 답 버튼 고르기 (민수님 부탁 11/10) ----------

    static final List<String> STAIRS = List.of("못 가요", "몇 칸은 괜찮아요", "괜찮아요");

    @Test
    void pickAnswerChoosesButton() throws Exception {
        when(gemini.structured(anyString(), any(), any())).thenReturn("{\"index\":0}");
        mvc.perform(post("/api/ai/pick-answer").contentType(MediaType.APPLICATION_JSON).content("""
                        {"question":"계단은 어떠세요?","choices":["못 가요","몇 칸은 괜찮아요","괜찮아요"],
                         "said":"무릎 때문에 계단은 좀…"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").value(0));
        // 질문·버튼·말이 번호와 함께 제미나이에 감
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> sent = ArgumentCaptor.forClass(List.class);
        verify(gemini).structured(anyString(), sent.capture(), any());
        assertThat(sent.getValue().toString()).contains("1. 몇 칸은 괜찮아요", "무릎 때문에 계단은 좀");
    }

    @Test
    void pickAnswerUnknownIsNull() throws Exception {
        when(gemini.structured(anyString(), any(), any())).thenReturn("{\"index\":-1}");
        mvc.perform(post("/api/ai/pick-answer").contentType(MediaType.APPLICATION_JSON).content("""
                        {"question":"계단은 어떠세요?","choices":["못 가요","괜찮아요"],"said":"오늘 날씨 좋네"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").doesNotExist());
        // 버튼 밖 번호도 지어낸 것으로 보고 null
        when(gemini.structured(anyString(), any(), any())).thenReturn("{\"index\":5}");
        assertThat(service.pickAnswer(new PickAnswerRequest("계단은요?", STAIRS, "음"))).containsEntry("index", null);
        when(gemini.structured(anyString(), any(), any())).thenReturn("{\"index\":\"0\"}");
        assertThat(service.pickAnswer(new PickAnswerRequest("계단은요?", STAIRS, "음"))).containsEntry("index", null);
    }

    @Test
    void pickAnswerChecksInput() {
        assertThatThrownBy(() -> service.pickAnswer(new PickAnswerRequest("계단은요?", STAIRS, " ")))
                .isInstanceOf(AppChatException.class).hasMessageContaining("said");
        assertThatThrownBy(() -> service.pickAnswer(new PickAnswerRequest("계단은요?", List.of("하나"), "응")))
                .hasMessageContaining("choices");
        assertThatThrownBy(() -> service.pickAnswer(new PickAnswerRequest("계단은요?", List.of("a", ""), "응")))
                .hasMessageContaining("답 버튼");
        assertThatThrownBy(() -> service.pickAnswer(null)).isInstanceOf(AppChatException.class);
        verify(gemini, never()).structured(anyString(), any(), any());
    }

    @Test
    void pickAnswerWithoutKeyIs503() throws Exception {
        when(gemini.available()).thenReturn(false);
        mvc.perform(post("/api/ai/pick-answer").contentType(MediaType.APPLICATION_JSON).content("""
                        {"question":"맞으세요?","choices":["네","아니요"],"said":"그런 것 같아요"}
                        """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
    }
}
