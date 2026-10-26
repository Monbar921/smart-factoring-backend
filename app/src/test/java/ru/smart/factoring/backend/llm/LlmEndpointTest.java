package ru.smart.factoring.backend.llm;

import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.support.Api;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM для сервиса моделей при LLM_MODE=stub (основной контекст тестов) и проверка запроса.
 */
class LlmEndpointTest extends AbstractSmartFactoringBackendTest {
    private static final int PORT = 8099;

    @Test
    void llmDisabledInStubMode() {
        final Api.Response response = LlmApi.chat(PORT, Map.of("messages", List.of(Map.of("role", "user", "content", "Привет"))));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body().get("code").asString()).isEqualTo("LLM_DISABLED");
    }

    @Test
    void rejectsInvalidRequest() {
        assertThat(LlmApi.chat(PORT, Map.of("messages", List.of())).status()).isEqualTo(400);
        assertThat(LlmApi.chat(PORT, Map.of("messages", List.of(Map.of("role", "tool", "content", "x")))).status()).isEqualTo(400);
        assertThat(LlmApi.chat(PORT, Map.of("messages", List.of(Map.of("role", "user", "content", " ")))).status()).isEqualTo(400);
        assertThat(LlmApi.chat(PORT, Map.of("messages", List.of(Map.of("role", "user", "content", "x")), "temperature", 3)).status())
                .isEqualTo(400);
    }
}
