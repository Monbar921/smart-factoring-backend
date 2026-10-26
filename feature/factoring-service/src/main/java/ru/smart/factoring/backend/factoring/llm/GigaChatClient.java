package ru.smart.factoring.backend.factoring.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.smart.factoring.backend.factoring.error.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Клиент GigaChat API (Р-12, Р-48).
 * <ul>
 *     <li>Токен OAuth по ключу авторизации, живёт 30 минут: кешируется и обновляется за минуту до истечения или по 401.</li>
 *     <li>Не больше parallelism одновременных запросов: прогон и сервис моделей делят один лимит тарифа.</li>
 *     <li>429, 5xx и сетевые сбои - до {@value #MAX_ATTEMPTS} попыток с паузой; остальные 4xx - сразу LLM_ERROR.</li>
 *     <li>TLS: сертификаты GigaChat выданы НУЦ Минцифры - PEM корневого сертификата задаётся в ca-cert.</li>
 * </ul>
 */
@Slf4j
public class GigaChatClient implements LlmClient {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(1);
    private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(1);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_BODY = 300;
    private static final String DEFAULT_MODEL = "GigaChat";
    private static final String DEFAULT_SCOPE = "GIGACHAT_API_PERS";

    private final LlmProperties.GigaChat properties;
    private final String model;
    private final String scope;
    private final RestClient client;
    private final Semaphore permits;
    private final Duration backoff;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public GigaChatClient(final LlmProperties.GigaChat properties, final int parallelism) {
        this(properties, parallelism, DEFAULT_BACKOFF);
    }

    GigaChatClient(final LlmProperties.GigaChat properties, final int parallelism, final Duration backoff) {
        if (properties == null || properties.authKey() == null || properties.authKey().isBlank()) {
            throw new IllegalStateException("LLM_MODE=gigachat, но не задан GIGACHAT_AUTH_KEY");
        }
        this.properties = properties;
        this.model = properties.model() == null || properties.model().isBlank() ? DEFAULT_MODEL : properties.model();
        this.scope = properties.scope() == null || properties.scope().isBlank() ? DEFAULT_SCOPE : properties.scope();
        this.permits = new Semaphore(parallelism, true);
        this.backoff = backoff;
        final HttpClient.Builder http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT);
        if (properties.caCert() != null && !properties.caCert().isBlank()) {
            http.sslContext(TrustedCertificates.withExtraCa(Path.of(properties.caCert())));
        }
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(properties.timeout() != null ? properties.timeout() : DEFAULT_TIMEOUT);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Completion complete(final CompletionRequest request) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("прервано ожидание очереди запросов");
        }
        try {
            return withRetries(body(request));
        } finally {
            permits.release();
        }
    }

    private Completion withRetries(final String body) {
        String failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                pause(attempt);
            }
            try {
                return parse(send(body));
            } catch (RetryableException e) {
                failure = e.getMessage();
                log.warn("GigaChat, попытка {} из {}: {}", attempt, MAX_ATTEMPTS, failure);
            }
        }
        throw unavailable(failure);
    }

    private String send(final String body) {
        final Reply reply = exchange(client.post()
                .uri(properties.apiUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body));
        if (reply.status() == HttpStatus.UNAUTHORIZED.value()) {
            invalidateToken();
            throw new RetryableException("токен не принят (401), запрошу новый");
        }
        return checked(reply, "chat/completions");
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

    private synchronized void invalidateToken() {
        token = null;
    }

    private Reply exchange(final RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((rq, response) -> new Reply(response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            throw new RetryableException("нет связи: " + e.getMessage());
        }
    }

    private String checked(final Reply reply, final String what) {
        final int status = reply.status();
        if (status == HttpStatus.OK.value()) {
            return reply.body();
        }
        final String message = what + " " + status + ": " + abbreviate(reply.body());
        if (status == HttpStatus.TOO_MANY_REQUESTS.value() || status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            throw new RetryableException(message);
        }
        throw error(message);
    }

    private String body(final CompletionRequest request) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", request.messages().stream()
                .map(message -> {
                    final Map<String, String> item = new LinkedHashMap<>();
                    item.put("role", message.role());
                    item.put("content", message.content());
                    return item;
                })
                .toList());
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        return mapper.writeValueAsString(body);
    }

    private Completion parse(final String body) {
        final JsonNode response = json(body);
        final JsonNode choice = response.path("choices").path(0);
        final String content = choice.path("message").path("content").asString(null);
        if (content == null) {
            throw error("в ответе нет choices[0].message.content");
        }
        final JsonNode usage = response.path("usage");
        return new Completion(content.trim(), response.path("model").asString(model),
                choice.path("finish_reason").asString(null),
                new Usage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0),
                        usage.path("total_tokens").asInt(0)));
    }

    private JsonNode json(final String body) {
        try {
            return mapper.readTree(body);
        } catch (JacksonException e) {
            throw error("ответ не JSON: " + abbreviate(body));
        }
    }

    private void pause(final int attempt) {
        try {
            Thread.sleep(backoff.multipliedBy(attempt - 1L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("прервана пауза между попытками");
        }
    }

    private static String abbreviate(final String text) {
        final String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_ERROR_BODY ? flat : flat.substring(0, MAX_ERROR_BODY) + "…";
    }

    private static ApiException unavailable(final String message) {
        return new ApiException("LLM_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "GigaChat недоступен: " + message, Map.of());
    }

    private static ApiException error(final String message) {
        return new ApiException("LLM_ERROR", HttpStatus.BAD_GATEWAY, "Ошибка GigaChat: " + message, Map.of());
    }

    private record Reply(int status, String body) {
    }

    /**
     * Сбой, после которого есть смысл повторить запрос.
     */
    private static final class RetryableException extends RuntimeException {
        RetryableException(final String message) {
            super(message);
        }
    }
}
