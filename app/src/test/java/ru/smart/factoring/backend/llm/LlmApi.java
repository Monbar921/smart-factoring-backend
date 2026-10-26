package ru.smart.factoring.backend.llm;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ru.smart.factoring.backend.support.Api;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * Запросы к /internal/llm/v1 - как их делает сервис моделей.
 */
final class LlmApi {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LlmApi() {
    }

    static Api.Response chat(final int port, final Object body) {
        return RestClient.create("http://localhost:" + port + "/internal/llm/v1").post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(body))
                .exchange((request, response) -> new Api.Response(response.getStatusCode().value(),
                        JSON.readTree(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8))));
    }
}
