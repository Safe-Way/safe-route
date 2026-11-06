package com.saferoute.assistant;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

/**
 * 구글 제미나이(Gemini) API를 서버가 대신 부른다 (무료 등급으로 쓸 수 있음). 키는 .env 의 GEMINI_API_KEY (서버에만).
 * 도구 목록(functionDeclarations)을 같이 보내면 제미나이가 어떤 도구를 어떤 값으로 부를지 정해 준다 (함수 호출).
 * 키가 없거나, 늦거나, 실패하면 null 을 돌려준다. 그러면 비서는 규칙만으로 답한다 (시연이 멈추지 않게).
 * 사용자 좌표는 절대 보내지 않는다 (NFR-07). 보낸 글·받은 글은 로그에 쓰지 않는다.
 * 문서: https://ai.google.dev/gemini-api/docs/function-calling
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final RestClient rest;
    private final String key;
    private final String model;

    @Autowired
    public GeminiClient(@Value("${api-keys.gemini:}") String key,
            @Value("${assistant.gemini-model:gemini-2.5-flash}") String model,
            @Value("${assistant.timeout-ms:6000}") int timeoutMs) {
        this(RestClient.builder().requestFactory(timeoutFactory(timeoutMs)), key, model);
    }

    /** 테스트에서 가짜 제미나이 서버를 붙일 때 쓴다 */
    GeminiClient(RestClient.Builder builder, String key, String model) {
        this.rest = builder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.key = key;
        this.model = model;
    }

    /** 키가 있어서 AI를 부를 수 있으면 true */
    public boolean available() {
        return key != null && !key.isBlank();
    }

    /**
     * 대화와 도구 목록을 보내고 제미나이 답(candidates[0].content)을 받는다.
     *
     * @param system   처음 주는 지시
     * @param contents 지금까지 대화 ({role, parts} 목록. 제미나이가 준 content 는 받은 그대로 다시 넣는다)
     * @param tools    도구 설명 (functionDeclarations)
     * @return content ({role:"model", parts:[...]}). 키 없음·실패·빈 답이면 null
     */
    public JsonNode chat(String system, List<Object> contents, List<Map<String, Object>> tools) {
        if (!available()) {
            return null;
        }
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", contents,
                "tools", List.of(Map.of("functionDeclarations", tools)),
                "generationConfig", Map.of("temperature", 0.2, "maxOutputTokens", 1024,
                        "thinkingConfig", Map.of("thinkingBudget", 0)));
        try {
            JsonNode res = rest.post()
                    .uri("/v1beta/models/{model}:generateContent", model)
                    .header("x-goog-api-key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode content = res == null ? null : res.path("candidates").path(0).path("content");
            return content == null || content.isMissingNode() || !content.path("parts").isArray() ? null : content;
        } catch (RestClientException e) {
            // 키가 든 주소나 보낸 글은 남기지 않고 종류만 남긴다
            log.warn("제미나이 호출 실패, 규칙으로 대신함: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(timeoutMs));
        f.setReadTimeout(Duration.ofMillis(timeoutMs));
        return f;
    }
}
