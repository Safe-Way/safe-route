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

    @Test
    void noUsageMetadataMeansNoUsage() {
        TokenUsage usage = new TokenUsage();
        GeminiClient.countTokens(null, "m", usage, 7);
        assertThat(usage.snapshot(List.of(), false)).isEqualTo(AssistantReply.Usage.NONE);
    }
}
