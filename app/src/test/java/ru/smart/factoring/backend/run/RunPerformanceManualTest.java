package ru.smart.factoring.backend.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.factoring.source.ManualUploadService;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Замер времени прогона (НФР-1.1, НФР-2.2; дорожная карта п. 1.3-9) на наборе docs/claude/tools/gen_perf_dataset.py:
 * объём демо (100 клиентов) или сегмента (22 641 клиент, Р-88).
 * <pre>
 * python -I docs/claude/tools/gen_perf_dataset.py &lt;папка&gt; 22641 30000 340000
 * mvn test -pl app -Dtest=RunPerformanceManualTest -DargLine="-Xmx768m -Dperf.dataset=&lt;папка&gt; -Dperf.repeat=false"
 * </pre>
 * -Xmx768m - куча backend на стенде (mem_limit 1g, 75 %). Строки «PERF» в выводе: импорт, прогон, шаги, метрики шагов 4-5.
 * perf.repeat=false - без второго прогона (на объёме сегмента он идёт столько же); perf.limitMinutes - порог проверки,
 * по умолчанию 10 (НФР-1.1).
 */
@EnabledIfSystemProperty(named = "perf.dataset", matches = ".+")
class RunPerformanceManualTest extends AbstractSmartFactoringBackendTest {
    private static final Duration WAIT = Duration.ofHours(4);
    private static final long MB = 1024 * 1024;

    @Autowired
    private ManualUploadService importService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void runsDatasetInTime() throws InterruptedException {
        TestDatabase.clean(jdbcTemplate);
        final long importStarted = System.nanoTime();
        importService.importGenerator(Path.of(System.getProperty("perf.dataset")));
        System.out.println("PERF import " + Duration.ofNanos(System.nanoTime() - importStarted).toMillis() + " ms, peak heap "
                + peakHeapMb() + " MB");
        System.out.println("PERF heap max " + Runtime.getRuntime().maxMemory() / MB + " MB");
        System.gc();
        ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);

        final JsonNode first = Fixture.runAndWait(Map.of(), WAIT.toMillis());
        print(first);
        System.out.println("PERF run peak heap " + peakHeapMb() + " MB");
        if (!"false".equals(System.getProperty("perf.repeat"))) {
            print(Fixture.runAndWait(Map.of(), WAIT.toMillis()));
        }
        System.out.println("PERF step 4 " + Fixture.metrics(first, 4));
        System.out.println("PERF step 5 " + Fixture.metrics(first, 5));
        System.out.println("PERF db " + jdbcTemplate.queryForObject(
                "select pg_size_pretty(pg_database_size(current_database()))", String.class));
        final long limitMs = Duration.ofMinutes(Long.parseLong(System.getProperty("perf.limitMinutes", "10"))).toMillis();
        assertThat(first.get("status").asString()).isIn("DONE", "DONE_WITH_WARNINGS");
        assertThat(first.get("durationMs").asLong()).isLessThan(limitMs);
    }

    /**
     * Пик занятой кучи с последнего сброса: сумма пиков пулов кучи (верхняя оценка).
     */
    private static long peakHeapMb() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum() / MB;
    }

    private static void print(final JsonNode run) {
        System.out.println("PERF run " + run.get("id") + " " + run.get("status") + " " + run.get("durationMs") + " ms");
        run.get("steps").forEach(step -> System.out.println("PERF   step " + step.get("stepNo") + " " + step.get("name")
                + " " + step.get("durationMs") + " ms"));
    }
}
