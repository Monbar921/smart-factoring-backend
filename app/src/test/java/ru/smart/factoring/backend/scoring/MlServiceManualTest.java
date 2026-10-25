package ru.smart.factoring.backend.scoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ручная проверка стыковки с сервисом smart-factoring-ml: прогон фикстуры через настоящий HTTP-клиент.
 * Запуск: сервис на 127.0.0.1:8000, затем
 * mvn test -Dtest=MlServiceManualTest -DargLine="-Dsmart-factoring.ml.mode=http -Dsmart-factoring.ml.base-url=http://127.0.0.1:8000"
 */
@EnabledIfSystemProperty(named = "smart-factoring.ml.mode", matches = "http")
class MlServiceManualTest extends AbstractSmartFactoringBackendTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void runsFixtureThroughMlService() throws InterruptedException {
        Fixture.importFixture(jdbcTemplate);
        final JsonNode run = Fixture.run(Map.of());
        System.out.println("ML RUN " + run.get("status") + " " + run.get("modelVersions"));
        System.out.println("ML STEP 4 " + Fixture.metrics(run, 4));
        System.out.println("ML STEP 5 " + Fixture.metrics(run, 5));

        assertThat(run.get("status").asString()).isEqualTo("DONE");
        assertThat(run.get("modelVersions").get("pd").asString()).isEqualTo("pd-heuristic-1");
        assertThat(Fixture.metrics(run, 4).get("scoringErrors").asInt()).isZero();
        assertThat(Fixture.metrics(run, 5).get("offers").asInt()).isPositive();
        TestDatabase.clean(jdbcTemplate);
    }
}
