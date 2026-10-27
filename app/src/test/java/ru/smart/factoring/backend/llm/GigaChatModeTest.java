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

import java.util.ArrayList;
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
        GIGA_CHAT.unknownModel = false;
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
        assertThat(run.get("modelVersions").get("llm").asString()).isEqualTo("gigachat:GigaChat-2:v1");
        final int offers = Fixture.metrics(run, 5).get("offers").asInt();
        assertThat(offers).isEqualTo(12);
        assertThat(jdbcTemplate.queryForList("select distinct reasons_text from offer", String.class)).containsExactly(FakeGigaChat.REASONS);
        assertThat(jdbcTemplate.queryForList("select distinct message_draft from offer", String.class)).containsExactly(FakeGigaChat.DRAFT);
        assertThat(GIGA_CHAT.chatRequests).hasSize(offers * 2);
        assertThat(Fixture.metrics(run, 5).get("textsBySource").get("LLM").asInt()).isEqualTo(offers);
        assertThat(log(run, "LLM_TEXTS")).hasSize(1);
        assertThat(log(run, "LLM_ERROR")).isEmpty();

        // повторный прогон на тех же данных - тексты из кеша, к GigaChat не обращается (Р-7)
        GIGA_CHAT.chatRequests.clear();
        final JsonNode second = Fixture.run(Map.of());
        assertThat(second.get("status").asString()).isEqualTo("DONE");
        assertThat(GIGA_CHAT.chatRequests).isEmpty();
        assertThat(Fixture.metrics(second, 5).get("textsBySource").get("CACHE").asInt()).isEqualTo(offers);
    }

    @Test
    void gigaChatFailureIsVisibleInRunLog() throws InterruptedException {
        GIGA_CHAT.unknownModel = true;
        Fixture.importFixture(jdbcTemplate);
        final JsonNode run = Fixture.run(Map.of());

        // предложения не блокируются, но прогон и шаг 5 - с предупреждением, причина - в журнале по каждой карточке
        assertThat(run.get("status").asString()).isEqualTo("DONE_WITH_WARNINGS");
        assertThat(run.get("steps").get(4).get("status").asString()).isEqualTo("WARNING");
        final JsonNode metrics = Fixture.metrics(run, 5);
        assertThat(metrics.get("offers").asInt()).isEqualTo(12);
        assertThat(metrics.get("withoutMessageDraft").asInt()).isEqualTo(12);
        assertThat(metrics.get("textsBySource").get("FAILED").asInt()).isEqualTo(12);
        assertThat(log(run, "LLM_TEXTS")).singleElement().asString().contains("без черновика из-за сбоя LLM 12");
        assertThat(log(run, "LLM_ERROR")).hasSize(12).allSatisfy(message -> assertThat(message)
                .contains("без черновика")
                .contains("модель 'GigaChat-2' не найдена")
                .contains("доступны: GigaChat-2, GigaChat-2-Pro"));
    }

    @Test
    void listsGigaChatModels() {
        final Api.Response response = Api.get("/llm/models");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("configured").asString()).isEqualTo("GigaChat-2");
        assertThat(response.body().get("available").toString()).isEqualTo("[\"GigaChat-2\",\"GigaChat-2-Pro\"]");
    }

    /**
     * Сообщения журнала прогона с кодом события.
     */
    private static List<String> log(final JsonNode run, final String eventCode) {
        final List<String> messages = new ArrayList<>();
        Api.get("/runs/" + run.get("id").asLong() + "/log?size=300").body().get("items").forEach(entry -> {
            if (eventCode.equals(entry.get("eventCode").asString())) {
                messages.add(entry.get("message").asString());
            }
        });
        return messages;
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
                .contains("\"max_tokens\":50")
                .contains("\"model\":\"GigaChat-2\""));
    }

    @Test
    void callerChoosesModel() {
        final Api.Response response = LlmApi.chat(port, Map.of(
                "messages", List.of(Map.of("role", "user", "content", "Привет")), "model", "GigaChat-2-Pro"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(GIGA_CHAT.chatRequests).singleElement().asString().contains("\"model\":\"GigaChat-2-Pro\"");
    }
}
