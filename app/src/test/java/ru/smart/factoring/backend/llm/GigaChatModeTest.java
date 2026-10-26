package ru.smart.factoring.backend.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.SmartFactoringBackendApplication;
import ru.smart.factoring.backend.support.Api;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM_MODE=gigachat против заглушки GigaChat: тексты карточек в прогоне, кеш текстов и LLM для сервиса моделей (Р-48).
 * Свой контекст Spring на случайном порту - основной контекст тестов работает с LLM_MODE=stub.
 */
@SpringBootTest(classes = SmartFactoringBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GigaChatModeTest extends AbstractSmartFactoringBackendTest {
    private static final FakeGigaChat GIGA_CHAT = new FakeGigaChat();

    @DynamicPropertySource
    static void gigaChat(final DynamicPropertyRegistry registry) {
        registry.add("smart-factoring.llm.mode", () -> "gigachat");
        registry.add("smart-factoring.llm.gigachat.auth-key", () -> "dGVzdDp0ZXN0");
        registry.add("smart-factoring.llm.gigachat.auth-url", () -> GIGA_CHAT.baseUrl() + "/api/v2/oauth");
        registry.add("smart-factoring.llm.gigachat.api-url", () -> GIGA_CHAT.baseUrl() + "/api/v1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        Api.usePort(port);
        GIGA_CHAT.chatRequests.clear();
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
        Api.useDefaultPort();
    }

    @Test
    void offerTextsComeFromGigaChatAndAreCached() throws InterruptedException {
        Fixture.importFixture(jdbcTemplate);
        final JsonNode run = Fixture.run(Map.of());

        assertThat(run.get("status").asString()).isEqualTo("DONE");
        assertThat(run.get("modelVersions").get("llm").asString()).isEqualTo("gigachat:GigaChat:v1");
        final int offers = Fixture.metrics(run, 5).get("offers").asInt();
        assertThat(offers).isEqualTo(12);
        assertThat(jdbcTemplate.queryForList("select distinct reasons_text from offer", String.class)).containsExactly(FakeGigaChat.REASONS);
        assertThat(jdbcTemplate.queryForList("select distinct message_draft from offer", String.class)).containsExactly(FakeGigaChat.DRAFT);
        assertThat(GIGA_CHAT.chatRequests).hasSize(offers * 2);

        // повторный прогон на тех же данных - тексты из кеша, к GigaChat не обращается (Р-7)
        GIGA_CHAT.chatRequests.clear();
        assertThat(Fixture.run(Map.of()).get("status").asString()).isEqualTo("DONE");
        assertThat(GIGA_CHAT.chatRequests).isEmpty();
    }

    @Test
    void mlServiceCallsGigaChatThroughBackend() {
        final Api.Response response = LlmApi.chat(port, Map.of(
                "messages", List.of(Map.of("role", "system", "content", "Ты аналитик"), Map.of("role", "user", "content", "Привет")),
                "temperature", 0.1,
                "maxTokens", 50));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("content").asString()).startsWith("Ответ на:");
        assertThat(response.body().get("model").asString()).isEqualTo("GigaChat:fake");
        assertThat(response.body().get("finishReason").asString()).isEqualTo("stop");
        assertThat(response.body().get("promptTokens").asInt()).isEqualTo(10);
        assertThat(GIGA_CHAT.chatRequests).singleElement().satisfies(request -> assertThat(request)
                .contains("{\"role\":\"system\",\"content\":\"Ты аналитик\"}")
                .contains("\"temperature\":0.1")
                .contains("\"max_tokens\":50"));
    }
}
