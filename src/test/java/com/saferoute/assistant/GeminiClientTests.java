package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
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
}
