package ru.smart.factoring.backend.factoring.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Общая часть клиентов LLM с API вида OpenAI: POST {api}/chat/completions и GET {api}/models (Р-74). Так устроены
 * и GigaChat, и on-prem серверы моделей (vLLM, Ollama, llama.cpp, LM Studio) - отличается только авторизация.
 * <ul>
 *     <li>Не больше parallelism одновременных запросов: прогон и сервис моделей делят один лимит.</li>
 *     <li>429, 5xx и сетевые сбои - до {@value #MAX_ATTEMPTS} попыток с паузой; 401 - {@link #invalidateAuthorization()}
 *     и повтор; остальные 4xx - сразу LLM_ERROR.</li>
 *     <li>TLS: дополнительный корневой сертификат PEM-файлом (НУЦ Минцифры, корпоративный УЦ банка).</li>
 * </ul>
 */
@Slf4j
abstract class AbstractChatCompletionsClient implements LlmClient {
    static final int MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ERROR_BODY = 300;

    protected final RestClient client;
    /** как LLM называется в сообщениях об ошибках: GigaChat, on-prem LLM */
    private final String name;
    /** переменная окружения с моделью по умолчанию - для подсказки, если модель не найдена */
    private final String modelVariable;
    private final String apiUrl;
    private final String model;
    private final Semaphore permits;
    private final Duration backoff;
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * @param caCert PEM-файл с дополнительным корневым сертификатом; пусто - только сертификаты JDK
     */
    AbstractChatCompletionsClient(final String name, final String modelVariable, final String apiUrl, final String model,
                          final Duration timeout, final String caCert, final int parallelism, final Duration backoff) {
        this.name = name;
        this.modelVariable = modelVariable;
        this.apiUrl = apiUrl;
        this.model = model;
        this.permits = new Semaphore(parallelism, true);
        this.backoff = backoff;
        final HttpClient.Builder http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT);
        if (caCert != null && !caCert.isBlank()) {
            http.sslContext(TrustedCertificates.withExtraCa(Path.of(caCert)));
        }
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(timeout != null ? timeout : DEFAULT_TIMEOUT);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Значение заголовка Authorization для запросов к API; null - без заголовка.
     */
    protected abstract String authorization();

    /**
     * API ответил 401: забыть токен, следующий {@link #authorization()} получит новый.
     */
    protected void invalidateAuthorization() {
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Completion complete(final CompletionRequest request) {
        final String requested = request.model() == null || request.model().isBlank() ? model : request.model().trim();
        final String body = body(request, requested);
        return guarded(() -> {
            final Reply reply = withRetries(() -> send(client.post()
                    .uri(apiUrl + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)));
            if (reply.status() == HttpStatus.NOT_FOUND.value() && reply.body().toLowerCase(Locale.ROOT).contains("model")) {
                throw error("модель '" + requested + "' не найдена в " + apiUrl + ", доступны: " + availableModels()
                        + (requested.equals(model) ? " - задайте одну из них в " + modelVariable : " - передайте одну из них в model"));
            }
            return parse(checked(reply, "chat/completions"), requested);
        });
    }

    @Override
    public List<String> models() {
        return guarded(() -> modelIds(checked(withRetries(() -> send(client.get().uri(apiUrl + "/models"))), "models")));
    }

    /**
     * Не больше parallelism запросов одновременно.
     */
    private <T> T guarded(final Supplier<T> call) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("прервано ожидание очереди запросов");
        }
        try {
            return call.get();
        } finally {
            permits.release();
        }
    }

    /**
     * Ответ, после которого повторять бессмысленно: 200 или 4xx кроме 401/429. 401 - новая авторизация и повтор.
     */
    private Reply withRetries(final Supplier<Reply> call) {
        String failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                pause(attempt);
            }
            try {
                final Reply reply = call.get();
                if (reply.status() == HttpStatus.UNAUTHORIZED.value()) {
                    invalidateAuthorization();
                    throw new RetryableException("токен не принят (401), запрошу новый");
                }
                if (reply.status() == HttpStatus.TOO_MANY_REQUESTS.value() || reply.status() >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                    throw new RetryableException(reply.status() + ": " + abbreviate(reply.body()));
                }
                return reply;
            } catch (RetryableException e) {
                failure = e.getMessage();
                log.warn("{}, попытка {} из {}: {}", name, attempt, MAX_ATTEMPTS, failure);
            }
        }
        throw unavailable(failure);
    }

    private Reply send(final RestClient.RequestHeadersSpec<?> request) {
        final String authorization = authorization();
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return exchange(request.accept(MediaType.APPLICATION_JSON));
    }

    /**
     * Для сообщения об ошибке: без повторов и без очереди - вызывается внутри уже занятого слота.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private String availableModels() {
        try {
            final Reply reply = send(client.get().uri(apiUrl + "/models"));
            return reply.status() == HttpStatus.OK.value() ? String.join(", ", modelIds(reply.body()))
                    : "не удалось получить (" + reply.status() + ")";
        } catch (RuntimeException e) {
            return "не удалось получить (" + e.getMessage() + ")";
        }
    }

    private List<String> modelIds(final String body) {
        final List<String> ids = new ArrayList<>();
        json(body).path("data").forEach(item -> {
            final String id = item.path("id").asString(null);
            if (id != null) {
                ids.add(id);
            }
        });
        return ids;
    }

    protected final Reply exchange(final RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((rq, response) -> new Reply(response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            throw new RetryableException("нет связи: " + e.getMessage());
        }
    }

    protected final String checked(final Reply reply, final String what) {
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

    private String body(final CompletionRequest request, final String requested) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", requested);
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

    private Completion parse(final String body, final String requested) {
        final JsonNode response = json(body);
        final JsonNode choice = response.path("choices").path(0);
        final String content = choice.path("message").path("content").asString(null);
        if (content == null) {
            throw error("в ответе нет choices[0].message.content");
        }
        final JsonNode usage = response.path("usage");
        return new Completion(content.trim(), response.path("model").asString(requested),
                choice.path("finish_reason").asString(null),
                new Usage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0),
                        usage.path("total_tokens").asInt(0)));
    }

    protected final JsonNode json(final String body) {
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

    private ApiException unavailable(final String message) {
        return new ApiException("LLM_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, name + " недоступен: " + message, Map.of());
    }

    protected final ApiException error(final String message) {
        return new ApiException("LLM_ERROR", HttpStatus.BAD_GATEWAY, "Ошибка " + name + ": " + message, Map.of());
    }

    protected record Reply(int status, String body) {
    }

    /**
     * Сбой, после которого есть смысл повторить запрос.
     */
    protected static final class RetryableException extends RuntimeException {
        RetryableException(final String message) {
            super(message);
        }
    }
}
