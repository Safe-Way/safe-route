package com.saferoute.assistant;

import java.nio.charset.StandardCharsets;
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
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

    static final String DEFAULT_MODEL = "gemini-3.6-flash";

    /** 첫 모델이 바쁘거나(503·429) 없으면(404) 차례로 물어볼 모델 */
    static final List<String> BACKUP_MODELS = List.of("gemini-3.5-flash-lite", "gemini-3.8-flash");

    /** 생각하기를 가장 짧게(minimal) 할 수 있는 모델. 나머지 3 이후 모델은 low 가 가장 짧다 */
    static final java.util.Set<String> MINIMAL_OK = java.util.Set.of("gemini-3.6-flash", "gemini-3.5-flash-lite");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest;
    private final String key;
    private final String model;
    private final List<String> models;
    /** 마지막으로 대답한 모델 순번. 다음 질문은 그 모델부터 묻는다 (바쁜 모델을 매번 기다리지 않게) */
    private volatile int working = 0;

    @Autowired
    public GeminiClient(@Value("${api-keys.gemini:}") String key,
            @Value("${assistant.gemini-model:}") String model,
            @Value("${assistant.timeout-ms:6000}") int timeoutMs) {
        this(RestClient.builder().requestFactory(timeoutFactory(timeoutMs)), key, model);
    }

    /** 테스트에서 가짜 제미나이 서버를 붙일 때 쓴다 */
    GeminiClient(RestClient.Builder builder, String key, String model) {
        this.rest = builder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.key = key;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        all.add(this.model);
        all.addAll(BACKUP_MODELS);
        this.models = List.copyOf(all);
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
        for (int i = 0; i < models.size(); i++) {
            int at = (working + i) % models.size();
            String m = models.get(at);
            try {
                Map<String, Object> body = Map.of(
                        "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                        "contents", contents,
                        "tools", List.of(Map.of("functionDeclarations", tools)),
                        "generationConfig", generationConfig(m));
                JsonNode content = ask(m, body);
                working = at;
                return content;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                // 구글이 준 오류 설명만 남긴다 (키는 머리글에만 있어서 여기에 안 나옴)
                log.warn("제미나이 호출 실패: {} {} (모델 {})", status, googleMessage(e.getResponseBodyAsString()), m);
                if (status != 503 && status != 429 && status != 404) {
                    break; // 키·요청이 틀린 경우는 다른 모델로 물어도 같다
                }
            } catch (RestClientException | JacksonException e) {
                log.warn("제미나이 호출 실패: {} {} (모델 {})", e.getClass().getSimpleName(), shortText(e.getMessage()), m);
                // 늦은 경우(시간 초과)에 다른 모델까지 기다리면 너무 오래 걸린다. 다음 질문부터 다음 모델을 쓴다
                working = (at + 1) % models.size();
                break;
            }
        }
        log.warn("제미나이를 못 써서 규칙으로 대답함");
        return null;
    }

    /** 모델 하나에 묻는다. 빈 답이면 null */
    private JsonNode ask(String m, Map<String, Object> body) {
        // 받은 바이트를 그대로 UTF-8 글자로 읽는다. 구글(또는 중간 프록시)이 종류를 application/octet-stream 으로
        // 보내는 경우가 있어서, 종류 표시에 기대는 변환기를 쓰지 않는다.
        String raw = rest.post()
                .uri("/v1beta/models/{model}:generateContent", m)
                .header("x-goog-api-key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    byte[] bytes = res.getBody().readAllBytes();
                    if (res.getStatusCode().isError()) {
                        throw new RestClientResponseException("gemini " + res.getStatusCode().value(),
                                res.getStatusCode(), res.getStatusText(), res.getHeaders(), bytes,
                                StandardCharsets.UTF_8);
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                });
        JsonNode res = raw == null ? null : JSON.readTree(raw);
        JsonNode content = res == null ? null : res.path("candidates").path(0).path("content");
        if (content == null || content.isMissingNode() || !content.path("parts").isArray()) {
            log.warn("제미나이 답이 비었음 (모델 {}, 끝난 이유 {})", m,
                    res == null ? "-" : res.path("candidates").path(0).path("finishReason").asString("-"));
            return null;
        }
        return content;
    }

    /** 구글 오류 본문 {"error":{"message":...}} 에서 설명만 꺼낸다 */
    static String googleMessage(String body) {
        try {
            return shortText(JSON.readTree(body).path("error").path("message").asString(""));
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String shortText(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ');
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }

    /**
     * 2.5 모델은 생각하기를 끄고(thinkingBudget 0) 빨리 답하게 한다.
     * 3 이후 모델은 thinkingBudget 대신 thinkingLevel을 써서, 가장 낮은 단계(minimal, 안 되는 모델은 low)로 둔다.
     */
    Map<String, Object> generationConfig(String m) {
        Map<String, Object> thinking = m.startsWith("gemini-2.")
                ? Map.of("thinkingBudget", 0)
                : Map.of("thinkingLevel", MINIMAL_OK.contains(m) ? "minimal" : "low");
        return Map.of("temperature", 0.2, "maxOutputTokens", 1024, "thinkingConfig", thinking);
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofMillis(timeoutMs));
        f.setReadTimeout(Duration.ofMillis(timeoutMs));
        return f;
    }
}
