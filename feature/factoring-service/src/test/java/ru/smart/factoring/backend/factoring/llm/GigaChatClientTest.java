package ru.smart.factoring.backend.factoring.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.factoring.error.ApiException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Клиент GigaChat против заглушки API на JDK HttpServer: токен, повторы, ошибки, предел одновременных запросов.
 */
class GigaChatClientTest {
    private static final String AUTH_KEY = "dGVzdC1pZDp0ZXN0LXNlY3JldA==";
    private static final String COMPLETION = """
            {"choices":[{"message":{"role":"assistant","content":"  Покупатель надёжный.  "},"index":0,"finish_reason":"stop"}],
             "created":1761400000,"model":"GigaChat:2.0.28.2","object":"chat.completion",
             "usage":{"prompt_tokens":120,"completion_tokens":15,"total_tokens":135}}""";

    private HttpServer server;
    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<String> chatRequests = new CopyOnWriteArrayList<>();
    private final List<String> chatAuthorizations = new CopyOnWriteArrayList<>();
    /** статусы ответов chat/completions по очереди; пусто - 200 */
    private final Queue<Integer> chatStatuses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger tokenNumber = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile long chatDelayMs;
    private volatile String chatErrorBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v2/oauth", exchange -> {
            final String body = read(exchange);
            tokenRequests.add(exchange.getRequestHeaders().getFirst("Authorization") + " | RqUID="
                    + exchange.getRequestHeaders().getFirst("RqUID") + " | " + body);
            final long expiresAt = Instant.now().plus(Duration.ofMinutes(30)).toEpochMilli();
            send(exchange, 200, "{\"access_token\":\"token-" + tokenNumber.incrementAndGet() + "\",\"expires_at\":" + expiresAt + "}");
        });
        server.createContext("/api/v1/chat/completions", exchange -> {
            final int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                chatRequests.add(read(exchange));
                chatAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                sleep(chatDelayMs);
                final Integer status = chatStatuses.poll();
                if (status == null || status == 200) {
                    send(exchange, 200, COMPLETION);
                } else {
                    send(exchange, status, chatErrorBody != null ? chatErrorBody : "{\"status\":" + status + ",\"message\":\"fake error\"}");
                }
            } finally {
                inFlight.decrementAndGet();
            }
        });
        server.createContext("/api/v1/models", exchange -> send(exchange, 200, """
                {"object":"list","data":[{"id":"GigaChat-2","object":"model","owned_by":"salutedevices"},
                {"id":"GigaChat-2-Pro","object":"model","owned_by":"salutedevices"}]}"""));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getsTokenAndCompletes() {
        final LlmClient.Completion completion = client(4).complete(request());

        assertThat(completion.content()).isEqualTo("Покупатель надёжный.");
        assertThat(completion.model()).isEqualTo("GigaChat:2.0.28.2");
        assertThat(completion.finishReason()).isEqualTo("stop");
        assertThat(completion.usage()).isEqualTo(new LlmClient.Usage(120, 15, 135));

        assertThat(tokenRequests).singleElement().satisfies(request -> assertThat(request)
                .startsWith("Basic " + AUTH_KEY + " | RqUID=")
                .matches(".*RqUID=[0-9a-f-]{36} .*")
                .endsWith("scope=GIGACHAT_API_PERS"));
        assertThat(chatAuthorizations).containsExactly("Bearer token-1");
        assertThat(chatRequests.getFirst())
                .contains("\"model\":\"GigaChat\"")
                .contains("{\"role\":\"system\",\"content\":\"Ты помощник\"}")
                .contains("\"temperature\":0.2")
                .contains("\"max_tokens\":100");
    }

    @Test
    void reusesTokenUntilExpiry() {
        final GigaChatClient client = client(4);
        client.complete(request());
        client.complete(request());

        assertThat(tokenRequests).hasSize(1);
        assertThat(chatAuthorizations).containsExactly("Bearer token-1", "Bearer token-1");
    }

    @Test
    void refreshesTokenOn401() {
        chatStatuses.add(401);

        final LlmClient.Completion completion = client(4).complete(request());

        assertThat(completion.content()).isEqualTo("Покупатель надёжный.");
        assertThat(tokenRequests).hasSize(2);
        assertThat(chatAuthorizations).containsExactly("Bearer token-1", "Bearer token-2");
    }

    @Test
    void retriesTooManyRequestsAndServerErrors() {
        chatStatuses.add(429);
        chatStatuses.add(503);

        assertThat(client(4).complete(request()).content()).isEqualTo("Покупатель надёжный.");
        assertThat(chatRequests).hasSize(3);
    }

    @Test
    void givesUpAfterThreeAttempts() {
        chatStatuses.addAll(List.of(500, 500, 500));

        assertThatThrownBy(() -> client(4).complete(request()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("LLM_UNAVAILABLE"))
                .hasMessageContaining("500");
        assertThat(chatRequests).hasSize(3);
    }

    @Test
    void doesNotRetryClientErrors() {
        chatStatuses.add(422);

        assertThatThrownBy(() -> client(4).complete(request()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("LLM_ERROR"))
                .hasMessageContaining("422");
        assertThat(chatRequests).hasSize(1);
    }

    @Test
    void unreachableServerIsUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> client(4).complete(request()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("LLM_UNAVAILABLE"));
    }

    @Test
    void limitsConcurrentRequests() throws Exception {
        chatDelayMs = 100;
        final GigaChatClient client = client(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            final List<Future<LlmClient.Completion>> results = pool.invokeAll(
                    List.of(() -> client.complete(request()), () -> client.complete(request()), () -> client.complete(request()),
                            () -> client.complete(request()), () -> client.complete(request()), () -> client.complete(request())));
            for (final Future<LlmClient.Completion> result : results) {
                assertThat(result.get().content()).isEqualTo("Покупатель надёжный.");
            }
        }
        assertThat(maxInFlight.get()).isEqualTo(2);
    }

    @Test
    void listsModels() {
        assertThat(client(4).models()).containsExactly("GigaChat-2", "GigaChat-2-Pro");
        assertThat(tokenRequests).hasSize(1);
    }

    @Test
    void unknownModelErrorListsAvailableModels() {
        chatStatuses.add(404);
        chatErrorBody = "{\"status\":404,\"message\":\"No such model\"}";

        assertThatThrownBy(() -> client(4).complete(request()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("LLM_ERROR"))
                .hasMessageContaining("модель 'GigaChat' не найдена")
                .hasMessageContaining("доступны: GigaChat-2, GigaChat-2-Pro")
                .hasMessageContaining("GIGACHAT_MODEL");
        assertThat(chatRequests).hasSize(1);
    }

    @Test
    void callerChoosesModelOrGetsDefault() {
        final GigaChatClient client = client(4);
        final List<LlmClient.Message> messages = List.of(new LlmClient.Message("user", "Оцени покупателя"));

        client.complete(new LlmClient.CompletionRequest(messages, null, null, "GigaChat-2-Pro"));
        client.complete(new LlmClient.CompletionRequest(messages, null, null, " "));
        client.complete(new LlmClient.CompletionRequest(messages, null, null));

        assertThat(chatRequests.get(0)).contains("\"model\":\"GigaChat-2-Pro\"");
        assertThat(chatRequests.get(1)).contains("\"model\":\"GigaChat\"");
        assertThat(chatRequests.get(2)).contains("\"model\":\"GigaChat\"");
    }

    @Test
    void unknownRequestedModelPointsToRequestField() {
        chatStatuses.add(404);
        chatErrorBody = "{\"status\":404,\"message\":\"No such model\"}";

        assertThatThrownBy(() -> client(4).complete(new LlmClient.CompletionRequest(
                List.of(new LlmClient.Message("user", "x")), null, null, "GigaChat-9")))
                .hasMessageContaining("модель 'GigaChat-9' не найдена")
                .hasMessageContaining("доступны: GigaChat-2, GigaChat-2-Pro - передайте одну из них в model");
    }

    @Test
    void requiresAuthKey() {
        assertThatThrownBy(() -> new GigaChatClient(properties(" "), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GIGACHAT_AUTH_KEY");
    }

    private GigaChatClient client(final int parallelism) {
        return new GigaChatClient(properties(AUTH_KEY), parallelism, Duration.ZERO);
    }

    private LlmProperties.GigaChat properties(final String authKey) {
        final String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new LlmProperties.GigaChat(authKey, "GIGACHAT_API_PERS", base + "/api/v2/oauth", base + "/api/v1", "GigaChat",
                Duration.ofSeconds(5), null);
    }

    private static LlmClient.CompletionRequest request() {
        return new LlmClient.CompletionRequest(
                List.of(new LlmClient.Message("system", "Ты помощник"), new LlmClient.Message("user", "Оцени покупателя")), 0.2, 100);
    }

    private static String read(final HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
