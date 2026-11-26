package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeminiClientTests {

    private static Object thinking(String model) {
        GeminiClient c = new GeminiClient(RestClient.builder(), "k", model);
        return c.generationConfig(model == null || model.isBlank() ? GeminiClient.DEFAULT_MODEL : model).get("thinkingConfig");
    }

    @Test
    void thinkingSettingFitsModel() {
        assertThat(thinking("gemini-2.5-flash")).isEqualTo(Map.of("thinkingBudget", 0));
        assertThat(thinking("gemini-3.8-flash")).isEqualTo(Map.of("thinkingLevel", "low"));
        assertThat(thinking("gemini-3.6-flash")).isEqualTo(Map.of("thinkingLevel", "minimal"));
    }

    @Test
    void blankModelUsesDefault() {
        assertThat(thinking("")).isEqualTo(Map.of("thinkingLevel", "minimal"));
        assertThat(thinking(null)).isEqualTo(Map.of("thinkingLevel", "minimal"));
    }

    @Test
    void keepsOnlyGoogleErrorMessage() {
        assertThat(GeminiClient.googleMessage("{\"error\":{\"code\":400,\"message\":\"Invalid value at thinking_level\"}}"))
                .isEqualTo("Invalid value at thinking_level");
        assertThat(GeminiClient.googleMessage("<html>")).isEmpty();
    }

    @Test
    void structuredAsksForJsonShapeAndReturnsText() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        GeminiClient c = new GeminiClient(b, "k", "gemini-test");
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andExpect(header("x-goog-api-key", "k"))
                .andExpect(content().string(Matchers.allOf(Matchers.containsString("\"responseMimeType\":\"application/json\""),
                        Matchers.containsString("\"responseSchema\""), Matchers.not(Matchers.containsString("functionDeclarations")))))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"{\\"a\\":"},{"text":"1}"}]}}]}
                        """, MediaType.APPLICATION_JSON));
        String text = c.structured("지시", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "안녕")))),
                Map.of("type", "OBJECT"));
        server.verify();
        assertThat(text).isEqualTo("{\"a\":1}");
    }

    @Test
    void structuredWithoutKeyIsNull() {
        assertThat(new GeminiClient(RestClient.builder(), "", "m").structured("s", List.of(), Map.of())).isNull();
    }

    @Test
    void chatAddsUsageMetadataOfEveryCall() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        GeminiClient c = new GeminiClient(b, "k", "gemini-test");
        String answer = """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"네"}]}}],
                 "usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":30,"thoughtsTokenCount":5,"totalTokenCount":1235}}
                """;
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess(answer, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess(answer, MediaType.APPLICATION_JSON));
        TokenUsage usage = new TokenUsage();
        List<Object> contents = List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "안녕"))));
        List<Map<String, Object>> tools = List.of(Map.of("name", "a"), Map.of("name", "b"));
        c.chat("지시", contents, tools, usage);
        c.chat("지시", contents, tools, usage);
        server.verify();
        assertThat(usage.snapshot(List.of("a", "b"), false)).isEqualTo(new AssistantReply.Usage(2400, 70, 2, List.of("a", "b"), false));
    }

    static final String GEN_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent";
    static final String SHORT_ANSWER = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"네"}]}}]}
            """;

    @Test
    void chatGivesUpWhenTimeRunsOut() {
        // 비서 답 시간 한도 (11-16): 제미나이가 2초 걸려도 남은 0.2초만 기다리고 null (비서는 규칙으로 답함)
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        GeminiClient c = new GeminiClient(b, "k", "gemini-test", 10_000);
        server.expect(requestTo(GEN_URL)).andRespond(request -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return withSuccess(SHORT_ANSWER, MediaType.APPLICATION_JSON).createResponse(request);
        });
        List<Object> contents = List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "안녕"))));
        long start = System.nanoTime();
        assertThat(c.chat("지시", contents, List.of(Map.of("name", "a")), new TokenUsage(), 200)).isNull();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
    }

    @Test
    void chatWithNoTimeLeftSendsNothing() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        // 기대가 하나도 없으면 verify()는 아무것도 보지 않으므로, "한 번도 오면 안 됨"을 걸어 둔다
        server.expect(ExpectedCount.never(), MockRestRequestMatchers.anything());
        GeminiClient c = new GeminiClient(b, "k", "gemini-test", 10_000);
        assertThat(c.chat("지시", List.of(), List.of(Map.of("name", "a")), new TokenUsage(), 0)).isNull();
        // 참고: leftMs<=0 막는 부분이 없어도 get(0)이 바로 시간 초과로 취소해 요청이 대개 안 나간다.
        // 그래서 이 테스트는 "바로 null"을 지키고, never()는 요청이 실제로 나갔을 때만 잡는다
        server.verify(); // 아무 요청도 안 감
    }

    @Test
    void chatWithinTimeGivesAnswer() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        GeminiClient c = new GeminiClient(b, "k", "gemini-test", 10_000);
        server.expect(requestTo(GEN_URL)).andRespond(withSuccess(SHORT_ANSWER, MediaType.APPLICATION_JSON));
        List<Object> contents = List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "안녕"))));
        assertThat(c.chat("지시", contents, List.of(Map.of("name", "a")), new TokenUsage(), 5000)).isNotNull();
        server.verify();
    }

    @Test
    void structuredGivesUpWhenTimeRunsOut() {
        // 앱 화면 AI 시간 한도 (11-26): 제미나이가 2초 걸려도 남은 0.2초만 기다리고 null (앱은 자기 규칙으로)
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        GeminiClient c = new GeminiClient(b, "k", "gemini-test", 10_000);
        server.expect(requestTo(GEN_URL)).andRespond(request -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return withSuccess(SHORT_ANSWER, MediaType.APPLICATION_JSON).createResponse(request);
        });
        List<Object> contents = List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "안녕"))));
        long start = System.nanoTime();
        assertThat(c.structured("지시", contents, Map.of("type", "object"), 200)).isNull();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
    }

    @Test
    void structuredWithinTimeGivesAnswer() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        GeminiClient c = new GeminiClient(b, "k", "gemini-test", 10_000);
        server.expect(requestTo(GEN_URL)).andRespond(withSuccess(SHORT_ANSWER, MediaType.APPLICATION_JSON));
        List<Object> contents = List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "안녕"))));
        assertThat(c.structured("지시", contents, Map.of("type", "object"), 5000)).isEqualTo("네");
        server.verify();
    }

    @Test
    void structuredWithNoTimeLeftSendsNothing() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(ExpectedCount.never(), MockRestRequestMatchers.anything());
        GeminiClient c = new GeminiClient(b, "k", "gemini-test", 10_000);
        assertThat(c.structured("지시", List.of(), Map.of("type", "object"), 0)).isNull();
        server.verify();
    }

    @Test
    void noUsageMetadataMeansNoUsage() {
        TokenUsage usage = new TokenUsage();
        GeminiClient.countTokens(null, "m", usage, 7);
        assertThat(usage.snapshot(List.of(), false)).isEqualTo(AssistantReply.Usage.NONE);
    }
}
