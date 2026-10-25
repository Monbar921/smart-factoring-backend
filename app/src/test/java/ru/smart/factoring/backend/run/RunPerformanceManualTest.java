package ru.smart.factoring.backend.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.factoring.source.DemoImportService;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ручная проверка НФР-1.1 на наборе объёма демо (docs/claude/tools/gen_perf_dataset.py):
 * mvn test -Dtest=RunPerformanceManualTest -DargLine="-Dperf.dataset=путь"
 */
@EnabledIfSystemProperty(named = "perf.dataset", matches = ".+")
class RunPerformanceManualTest extends AbstractSmartFactoringBackendTest {
    private static final long TEN_MINUTES_MS = 600_000;

    @Autowired
    private DemoImportService importService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void runsDemoVolumeInTime() throws InterruptedException {
        TestDatabase.clean(jdbcTemplate);
        importService.importGenerator(Path.of(System.getProperty("perf.dataset")));
        final JsonNode first = Fixture.run(Map.of());
        final JsonNode second = Fixture.run(Map.of());
        for (final JsonNode run : new JsonNode[]{first, second}) {
            System.out.println("PERF run " + run.get("id") + " " + run.get("status") + " " + run.get("durationMs") + " ms");
            run.get("steps").forEach(step -> System.out.println("PERF   step " + step.get("stepNo") + " " + step.get("durationMs") + " ms"));
        }
        System.out.println("PERF step 5 " + Fixture.metrics(first, 5));
        assertThat(first.get("durationMs").asLong()).isLessThan(TEN_MINUTES_MS);
        TestDatabase.clean(jdbcTemplate);
    }
}
