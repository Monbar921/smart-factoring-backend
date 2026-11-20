package ru.smart.factoring.backend.factoring.llm;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Клиент GigaChat API (Р-12, Р-48). Запросы, повторы и предел одновременных запросов - {@link AbstractChatCompletionsClient};
 * здесь - токен OAuth по ключу авторизации: живёт 30 минут, кешируется и обновляется за минуту до истечения или по 401.
 * TLS: сертификаты GigaChat выданы НУЦ Минцифры - PEM корневого сертификата задаётся в ca-cert.
 */
public class GigaChatClient extends AbstractChatCompletionsClient {
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(1);
    private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(1);
    /** базовая модель на api.giga.chat; прежнего имени GigaChat там нет (No such model) */
    private static final String DEFAULT_MODEL = "GigaChat-2";
    private static final String DEFAULT_SCOPE = "GIGACHAT_API_PERS";

    private final LlmProperties.GigaChat properties;
    private final String scope;
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public GigaChatClient(final LlmProperties.GigaChat properties, final int parallelism) {
        this(properties, parallelism, DEFAULT_BACKOFF);
    }

    GigaChatClient(final LlmProperties.GigaChat properties, final int parallelism, final Duration backoff) {
        super("GigaChat", "GIGACHAT_MODEL", required(properties).apiUrl(),
                properties.model() == null || properties.model().isBlank() ? DEFAULT_MODEL : properties.model(),
                properties.timeout(), properties.caCert(), parallelism, backoff);
        this.properties = properties;
        this.scope = properties.scope() == null || properties.scope().isBlank() ? DEFAULT_SCOPE : properties.scope();
    }

    private static LlmProperties.GigaChat required(final LlmProperties.GigaChat properties) {
        if (properties == null || properties.authKey() == null || properties.authKey().isBlank()) {
            throw new IllegalStateException("LLM_MODE=gigachat, но не задан GIGACHAT_AUTH_KEY");
        }
        return properties;
    }

    @Override
    protected String authorization() {
        return "Bearer " + token();
    }

    @Override
    protected synchronized void invalidateAuthorization() {
        token = null;
    }

    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt.minus(TOKEN_MARGIN))) {
            return token;
        }
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("scope", scope);
        final Reply reply = exchange(client.post()
                .uri(properties.authUrl())
                .header(HttpHeaders.AUTHORIZATION, "Basic " + properties.authKey())
                .header("RqUID", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form));
        final JsonNode response = json(checked(reply, "oauth"));
        token = response.path("access_token").asString(null);
        if (token == null) {
            throw error("в ответе oauth нет access_token");
        }
        tokenExpiresAt = Instant.ofEpochMilli(response.path("expires_at").asLong(0));
        return token;
    }
}
