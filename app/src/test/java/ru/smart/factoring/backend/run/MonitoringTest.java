package ru.smart.factoring.backend.run;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.support.Api;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Страница мониторинга (Р-87, дорожная карта п. 1.3-12): открывается по последнему запуску - версии моделей,
 * распределение оценок, PSI к прошлому запуску и к эталону модели, тексты LLM.
 */
class MonitoringTest extends AbstractSmartFactoringBackendTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        Fixture.importFixture(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void showsModelsDistributionsAndPsiByLastRun() throws InterruptedException {
        final JsonNode first = Fixture.run(Map.of());
        final JsonNode firstMonitoring = Api.get("/runs/" + first.get("id").asLong() + "/monitoring").body();

        assertThat(firstMonitoring.get("models").get("pd").asString()).isNotBlank();
        assertThat(firstMonitoring.get("models").get("llm").asString()).isEqualTo("stub");
        final JsonNode pd = firstMonitoring.get("pd");
        assertThat(pd.get("buyers").asInt()).isPositive();
        assertThat(pd.get("levels")).hasSize(4);
        assertThat(pd.get("levels")).extracting(bucket -> bucket.get("label").asString()).containsExactly("A", "B", "C", "D");
        // первый запуск на этой версии модели - сравнивать не с чем; у заглушки моделей нет эталона
        assertThat(pd.get("psiPrevious").isNull()).isTrue();
        assertThat(pd.get("psiReference").isNull()).isTrue();
        assertThat(firstMonitoring.get("delay").get("buckets")).hasSize(5);
        // заглушка LLM: тексты шаблоном, ответов LLM нет - доли отклонённых нет
        final JsonNode llm = firstMonitoring.get("llm");
        assertThat(llm.get("cards").asInt()).isPositive();
        assertThat(llm.get("answers").asInt()).isZero();
        assertThat(llm.get("rejectedShare").isNull()).isTrue();

        // второй запуск на тех же данных: распределение то же - PSI 0, «стабильно»
        final JsonNode second = Fixture.run(Map.of());
        final JsonNode monitoring = Api.get("/runs/" + second.get("id").asLong() + "/monitoring").body();
        final JsonNode psi = monitoring.get("pd").get("psiPrevious");
        assertThat(psi.get("value").decimalValue()).isZero();
        assertThat(psi.get("status").asString()).isEqualTo("STABLE");
        assertThat(psi.get("baselineRunId").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(monitoring.get("delay").get("psiPrevious").get("status").asString()).isEqualTo("STABLE");
        assertThat(monitoring.get("history")).hasSize(2);
        assertThat(monitoring.get("history").get(0).get("runId").asLong()).isEqualTo(second.get("id").asLong());
    }

    @Test
    void unknownRunIsNotFound() {
        assertThat(Api.get("/runs/999999/monitoring").status()).isEqualTo(404);
    }
}
