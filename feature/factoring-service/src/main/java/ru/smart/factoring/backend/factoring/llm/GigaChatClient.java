package ru.smart.factoring.backend.factoring.llm;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Клиент GigaChat API: токен OAuth по ключу авторизации (живёт 30 минут) и chat/completions.
 * <p>
 * Сертификаты GigaChat выданы НУЦ Минцифры: корневой сертификат должен быть в truststore JVM стенда.
 */
public class GigaChatClient {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(1);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final LlmProperties.GigaChat properties;
    private final RestClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public GigaChatClient(final LlmProperties.GigaChat properties) {
        this.properties = properties;
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(properties.timeout() != null ? properties.timeout() : DEFAULT_TIMEOUT);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public String model() {
        return properties.model();
    }

    public String complete(final String systemPrompt, final String userPrompt) {
        final Map<String, Object> request = Map.of(
                "model", properties.model(),
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        final String body = client.post()
                .uri(properties.apiUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(request))
                .retrieve()
                .body(String.class);
        final JsonNode response = mapper.readTree(body);
        return response.get("choices").get(0).get("message").get("content").asString().trim();
    }

    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt.minus(TOKEN_MARGIN))) {
            return token;
        }
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("scope", properties.scope());
        final String body = client.post()
                .uri(properties.authUrl())
                .header(HttpHeaders.AUTHORIZATION, "Basic " + properties.authKey())
                .header("RqUID", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        final JsonNode response = mapper.readTree(body);
        token = response.get("access_token").asString();
        tokenExpiresAt = Instant.ofEpochMilli(response.get("expires_at").asLong());
        return token;
    }
}
