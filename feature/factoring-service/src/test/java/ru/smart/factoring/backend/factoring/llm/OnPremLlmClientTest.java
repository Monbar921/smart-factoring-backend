package ru.smart.factoring.backend.factoring.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.factoring.error.ApiException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * On-prem LLM (Р-74) против заглушки сервера с API вида OpenAI (как у vLLM и Ollama): без токена OAuth,
 * с ключом или без, повторы на 5xx.
 */
class OnPremLlmClientTest {
    private HttpServer server;
    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger failuresLeft = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (failuresLeft.getAndDecrement() > 0) {
                send(exchange, 503, "{\"error\":\"model is loading\"}");
                return;
            }
            send(exchange, 200, """
                    {"id":"chatcmpl-1","object":"chat.completion","model":"t-lite",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"Ответ модели"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":20,"completion_tokens":4,"total_tokens":24}}""");
        });
        server.createContext("/v1/models", exchange -> send(exchange, 200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"t-lite\",\"object\":\"model\"},{\"id\":\"qwen2.5:7b\",\"object\":\"model\"}]}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void completesWithoutKey() {
        final LlmClient.Completion completion = client(null).complete(request());

        assertThat(completion.content()).isEqualTo("Ответ модели");
        assertThat(completion.usage()).isEqualTo(new LlmClient.Usage(20, 4, 24));
        assertThat(authorizations).containsExactly("null");
        assertThat(bodies).singleElement().asString().contains("\"model\":\"t-lite\"", "\"max_tokens\":100");
    }

    @Test
    void sendsKeyWhenSet() {
        client("secret-key").complete(request());

        assertThat(authorizations).containsExactly("Bearer secret-key");
    }

    @Test
    void retriesWhileModelIsLoading() {
        failuresLeft.set(2);

        assertThat(client(null).complete(request()).content()).isEqualTo("Ответ модели");
        assertThat(bodies).hasSize(3);
    }

    @Test
    void failureNamesOnPremLlm() {
        failuresLeft.set(AbstractChatCompletionsClient.MAX_ATTEMPTS);

        assertThatThrownBy(() -> client(null).complete(request()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("on-prem LLM недоступен")
                .hasMessageContaining("503");
    }

    @Test
    void listsModels() {
        assertThat(client(null).models()).containsExactly("t-lite", "qwen2.5:7b");
    }

    @Test
    void requiresUrlAndModel() {
        assertThatThrownBy(() -> new OnPremLlmClient(new LlmProperties.OnPrem(" ", null, "t-lite", null, null), 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ONPREM_LLM_URL");
        assertThatThrownBy(() -> new OnPremLlmClient(new LlmProperties.OnPrem(baseUrl(), null, "", null, null), 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ONPREM_LLM_MODEL");
    }

    private OnPremLlmClient client(final String apiKey) {
        return new OnPremLlmClient(new LlmProperties.OnPrem(baseUrl(), apiKey, "t-lite", Duration.ofSeconds(5), null), 2, Duration.ZERO);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static LlmClient.CompletionRequest request() {
        return new LlmClient.CompletionRequest(
                List.of(new LlmClient.Message("system", "Ты помощник"), new LlmClient.Message("user", "Привет")), 0.2, 100);
    }

    private static void send(final com.sun.net.httpserver.HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
