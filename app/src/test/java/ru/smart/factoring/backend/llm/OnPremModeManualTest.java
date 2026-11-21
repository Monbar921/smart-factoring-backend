package ru.smart.factoring.backend.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ручная проверка LLM_MODE=onprem (Р-74) на настоящей модели в контуре: анализ фикстуры, тексты карточек от локальной
 * LLM с маскированием. Сервер с API вида OpenAI (Ollama, LM Studio, vLLM) должен быть запущен:
 * mvn test -pl app -Dtest=OnPremModeManualTest
 *   -DargLine="-Donprem.url=http://127.0.0.1:11434/v1 -Donprem.model=qwen2.5:7b-instruct"
 * Необязательно: -Donprem.max-tokens=2048 (модели с «мышлением»), -Donprem.parallelism=1.
 */
@EnabledIfSystemProperty(named = "onprem.url", matches = ".+")
@SpringBootTest(classes = SmartFactoringBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnPremModeManualTest extends AbstractSmartFactoringBackendTest {

    @DynamicPropertySource
    static void onPrem(final DynamicPropertyRegistry registry) {
        registry.add("smart-factoring.llm.mode", () -> "onprem");
        registry.add("smart-factoring.llm.onprem.api-url", () -> System.getProperty("onprem.url"));
        registry.add("smart-factoring.llm.onprem.model", () -> System.getProperty("onprem.model"));
        registry.add("smart-factoring.llm.onprem.timeout", () -> System.getProperty("onprem.timeout", "300s"));
        registry.add("smart-factoring.llm.max-tokens", () -> System.getProperty("onprem.max-tokens", "512"));
        registry.add("smart-factoring.llm.parallelism", () -> System.getProperty("onprem.parallelism", "1"));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        Api.usePort(port);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
        Api.useDefaultPort();
    }

    @Test
    void offerTextsComeFromLocalModel() throws InterruptedException {
        Fixture.importFixture(jdbcTemplate);
        final long started = System.currentTimeMillis();
        final JsonNode run = Fixture.runAndWait(Map.of(), 30 * 60_000L);
        System.out.println("ONPREM run " + run.get("status") + " " + run.get("modelVersions") + " "
                + (System.currentTimeMillis() - started) / 1000 + " s");
        System.out.println("ONPREM step 5 " + Fixture.metrics(run, 5));
        jdbcTemplate.queryForList("""
                select u.number, b.name as buyer, o.reasons_text, o.message_draft
                from offer o join upd u on u.id = o.upd_id join buyer b on b.id = o.buyer_id order by u.number""")
                .forEach(offer -> System.out.println("ONPREM offer " + offer));

        assertThat(run.get("modelVersions").get("llm").asString()).startsWith("onprem:");
        assertThat(Fixture.metrics(run, 5).get("textsBySource").get("LLM").asInt()).isPositive();
        // ни одной недоставленной метки и ни одного рассуждения модели в текстах менеджеру
        assertThat(jdbcTemplate.queryForList("select reasons_text || ' ' || coalesce(message_draft, '') from offer", String.class))
                .allSatisfy(text -> assertThat(text).doesNotContain("CLIENT_1", "BUYER_1", "INN_1", "UPD_1", "<think>"));
    }
}
