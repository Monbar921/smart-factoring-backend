package ru.smart.factoring.backend.support;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP-клиент тестов к API backend (сервер тестов слушает server.port из test application.yml).
 */
public final class Api {
    private static final String BASE_URL = "http://localhost:8099/internal/rest";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RestClient CLIENT = RestClient.create(BASE_URL);

    private Api() {
    }

    public static Response get(final String path) {
        return CLIENT.get().uri(path).exchange((request, response) -> read(response.getStatusCode(), response.getBody().readAllBytes()));
    }

    public static Response post(final String path, final Object body) {
        final RestClient.RequestBodySpec spec = CLIENT.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec.body(JSON.writeValueAsString(body));
        }
        return spec.exchange((request, response) -> read(response.getStatusCode(), response.getBody().readAllBytes()));
    }

    private static Response read(final HttpStatusCode status, final byte[] body) {
        try {
            final String text = new String(body, StandardCharsets.UTF_8);
            return new Response(status.value(), text.isBlank() ? null : JSON.readTree(text));
        } catch (RuntimeException e) {
            throw new UncheckedIOException(new IOException("Не JSON: " + new String(body, StandardCharsets.UTF_8), e));
        }
    }

    public record Response(int status, JsonNode body) {
    }
}
