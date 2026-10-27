package ru.smart.factoring.backend.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Заглушка GigaChat API для интеграционных тестов: токен OAuth и chat/completions.
 * Ответ зависит от системного промпта: причины оценки, черновик сообщения или эхо последнего сообщения.
 */
final class FakeGigaChat {
    static final String REASONS = "Причины от GigaChat";
    static final String DRAFT = "Черновик от GigaChat";

    private final HttpServer server;
    final List<String> chatRequests = new CopyOnWriteArrayList<>();
    /** true - chat/completions отвечает 404 No such model, как GigaChat на неизвестную модель */
    volatile boolean unknownModel;

    FakeGigaChat() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/api/v1/models", exchange -> send(exchange, 200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"GigaChat-2\"},{\"id\":\"GigaChat-2-Pro\"}]}"));
        server.createContext("/api/v2/oauth", exchange -> send(exchange, 200,
                "{\"access_token\":\"fake-token\",\"expires_at\":" + Instant.now().plus(Duration.ofMinutes(30)).toEpochMilli() + "}"));
        server.createContext("/api/v1/chat/completions", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            chatRequests.add(body);
            if (unknownModel) {
                send(exchange, 404, "{\"status\":404,\"message\":\"No such model\"}");
                return;
            }
            final String content;
            if (body.contains("Объясни менеджеру")) {
                content = REASONS;
            } else if (body.contains("Напиши черновик")) {
                content = DRAFT;
            } else {
                content = "Ответ на: " + body.length();
            }
            send(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant","content":"%s"},"index":0,"finish_reason":"stop"}],
                     "model":"GigaChat:fake","usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}""".formatted(content));
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void send(final HttpExchange exchange, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
