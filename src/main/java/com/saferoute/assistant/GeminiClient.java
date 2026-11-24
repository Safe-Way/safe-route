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
    /** 호출 한 번을 기다리는 시간 ms (assistant.timeout-ms). 테스트 생성자는 정하지 않음 */
    private final long callTimeoutMs;
    /** 남은 시간이 호출 한도보다 짧을 때 그 시간만 기다리려고 따로 돌리는 곳 (가벼운 가상 스레드) */
    private static final java.util.concurrent.ExecutorService WAITER =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    /** 마지막으로 대답한 모델 순번. 다음 질문은 그 모델부터 묻는다 (바쁜 모델을 매번 기다리지 않게) */
    private volatile int working = 0;

    @Autowired
    public GeminiClient(@Value("${api-keys.gemini:}") String key,
            @Value("${assistant.gemini-model:}") String model,
            @Value("${assistant.timeout-ms:6000}") int timeoutMs, com.saferoute.common.ApiUsage usage) {
        // 하루 호출 수를 센다 (api_call_count, 사용량 알림 2026-11-24). 모델을 바꿔 다시 묻는 것도 한 번씩
        this(RestClient.builder().requestFactory(timeoutFactory(timeoutMs)).requestInterceptor(usage.counter("gemini")),
                key, model, timeoutMs);
    }

    /** 테스트에서 가짜 제미나이 서버를 붙일 때 쓴다 */
    GeminiClient(RestClient.Builder builder, String key, String model) {
        this(builder, key, model, Long.MAX_VALUE);
    }

    GeminiClient(RestClient.Builder builder, String key, String model, long callTimeoutMs) {
        this.callTimeoutMs = callTimeoutMs;
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
        return chat(system, contents, tools, null);
    }

    /**
     * chat 과 같고, 남은 시간(leftMs)만큼만 기다린다 (비서 답 시간 한도, 2026-11-16).
     * 남은 시간이 호출 한도(assistant.timeout-ms)보다 길면 그냥 부른다. 짧으면 따로 불러 그 시간만 기다리고,
     * 넘으면 null (비서는 규칙으로 답한다). 넘긴 호출은 뒤에서 호출 한도까지 돌다 끝나고 답은 버린다
     */
    public JsonNode chat(String system, List<Object> contents, List<Map<String, Object>> tools, TokenUsage usage,
            long leftMs) {
        if (!available()) {
            return null;
        }
        if (leftMs <= 0) {
            return null;
        }
        if (leftMs >= callTimeoutMs) {
            return chat(system, contents, tools, usage);
        }
        java.util.concurrent.Future<JsonNode> call = WAITER.submit(() -> chat(system, contents, tools, usage));
        try {
            return call.get(leftMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            call.cancel(true);
            log.warn("제미나이가 남은 시간({}ms) 안에 답하지 않아 규칙으로 대답함", leftMs);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("제미나이 호출 실패: {}", e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
            return null;
        }
    }

    /** chat 과 같고, 쓴 토큰을 usage 에 더한다 (null 이면 로그만) */
    public JsonNode chat(String system, List<Object> contents, List<Map<String, Object>> tools, TokenUsage usage) {
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
                JsonNode content = ask(m, body, usage, tools.size());
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

    /**
     * 정해진 JSON 모양(responseSchema)으로만 답하게 묻는다 (앱의 말로 맞추기·지도 비서용, 도구 없음).
     *
     * @return 제미나이가 준 JSON 글자 (parts 의 text 를 이은 것). 키 없음·실패·빈 답이면 null
     */
    public String structured(String system, List<Object> contents, Map<String, Object> schema) {
        if (!available()) {
            return null;
        }
        for (int i = 0; i < models.size(); i++) {
            int at = (working + i) % models.size();
            String m = models.get(at);
            try {
                Map<String, Object> config = new java.util.LinkedHashMap<>(generationConfig(m));
                config.put("responseMimeType", "application/json");
                config.put("responseSchema", schema);
                config.put("maxOutputTokens", 400);
                Map<String, Object> body = Map.of(
                        "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                        "contents", contents,
                        "generationConfig", config);
                JsonNode content = ask(m, body, null, 0);
                working = at;
                if (content == null) {
                    return null;
                }
                StringBuilder text = new StringBuilder();
                for (JsonNode part : content.path("parts")) {
                    text.append(part.path("text").asString(""));
                }
                return text.isEmpty() ? null : text.toString();
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                log.warn("제미나이 호출 실패: {} {} (모델 {})", status, googleMessage(e.getResponseBodyAsString()), m);
                if (status != 503 && status != 429 && status != 404) {
                    break;
                }
            } catch (RestClientException | JacksonException e) {
                log.warn("제미나이 호출 실패: {} {} (모델 {})", e.getClass().getSimpleName(), shortText(e.getMessage()), m);
                working = (at + 1) % models.size();
                break;
            }
        }
        return null;
    }

    /** 모델 하나에 묻는다. 빈 답이면 null. 쓴 토큰(usageMetadata)은 로그에 남기고 usage 에 더한다 */
    private JsonNode ask(String m, Map<String, Object> body, TokenUsage usage, int toolCount) {
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
        countTokens(res, m, usage, toolCount);
        JsonNode content = res == null ? null : res.path("candidates").path(0).path("content");
        if (content == null || content.isMissingNode() || !content.path("parts").isArray()) {
            log.warn("제미나이 답이 비었음 (모델 {}, 끝난 이유 {})", m,
                    res == null ? "-" : res.path("candidates").path(0).path("finishReason").asString("-"));
            return null;
        }
        return content;
    }

    /**
     * 답의 usageMetadata (promptTokenCount 입력, candidatesTokenCount 출력, thoughtsTokenCount 생각, totalTokenCount 합)를
     * 로그에 남기고 usage 에 더한다 (비서 토큰 줄이기 R1). 칸이 없으면 아무것도 안 함
     */
    static void countTokens(JsonNode res, String model, TokenUsage usage, int toolCount) {
        JsonNode u = res == null ? null : res.path("usageMetadata");
        if (u == null || u.isMissingNode() || !u.isObject()) {
            return;
        }
        int in = u.path("promptTokenCount").asInt(0);
        int out = u.path("candidatesTokenCount").asInt(0);
        int thought = u.path("thoughtsTokenCount").asInt(0);
        int total = u.path("totalTokenCount").asInt(in + out + thought);
        log.info("제미나이 토큰: 입력 {} 출력 {} 생각 {} 합 {} (모델 {}, 도구 {}개)", in, out, thought, total, model, toolCount);
        if (usage != null) {
            usage.add(model, in, out, thought);
        }
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
