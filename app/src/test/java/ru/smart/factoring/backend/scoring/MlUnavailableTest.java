package ru.smart.factoring.backend.scoring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.SmartFactoringBackendApplication;
import ru.smart.factoring.backend.support.Api;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ML_MODE=http, сервис моделей недоступен: прогон не падает, УПД получают «ошибку оценки» и без предложения (НФР-3).
 */
@SpringBootTest(classes = SmartFactoringBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"smart-factoring.ml.mode=http", "smart-factoring.ml.base-url=http://127.0.0.1:9"})
class MlUnavailableTest extends AbstractSmartFactoringBackendTest {

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
    void runCompletesWithScoringErrors() throws InterruptedException {
        Fixture.importFixture(jdbcTemplate);
        final JsonNode run = Fixture.run(Map.of());

        assertThat(run.get("status").asString()).isEqualTo("DONE_WITH_WARNINGS");
        assertThat(run.get("modelVersions").get("pd").asString()).isEqualTo("unavailable");
        assertThat(Fixture.metrics(run, 4).get("scoringErrors").asInt()).isPositive();
        assertThat(Fixture.metrics(run, 5).get("offers").asInt()).isZero();
    }
}
