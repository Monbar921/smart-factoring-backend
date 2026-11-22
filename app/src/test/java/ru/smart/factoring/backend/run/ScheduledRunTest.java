package ru.smart.factoring.backend.run;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.factoring.run.ScheduledRun;
import ru.smart.factoring.backend.support.Api;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ночной прогон по расписанию (Р-85, дорожная карта п. 1.3-10): стартует сам, виден в истории как «по расписанию»;
 * на тех же данных второй раз не запускается - отказ в журнале попыток.
 */
class ScheduledRunTest extends AbstractSmartFactoringBackendTest {
    private static final long POLL_MS = 100;
    private static final int MAX_POLLS = 600;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ScheduledRun scheduledRun;

    @BeforeEach
    void setUp() {
        Fixture.importFixture(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void nightlyRunStartsByItselfAndIsSeenInHistory() throws InterruptedException {
        final Long runId = scheduledRun.startNow();
        assertThat(runId).isNotNull();
        final JsonNode run = awaitFinished(runId);

        assertThat(run.get("status").asString()).isIn("DONE", "DONE_WITH_WARNINGS");
        assertThat(run.get("initiatorType").asString()).isEqualTo("SCHEDULE");
        assertThat(run.get("initiator").asString()).isEqualTo(ScheduledRun.INITIATOR);

        final JsonNode history = Api.get("/runs").body().get("items").get(0);
        assertThat(history.get("id").asLong()).isEqualTo(runId);
        assertThat(history.get("initiator").asString()).isEqualTo("по расписанию");
        assertThat(history.get("initiatorRole").asString()).isEqualTo("система");

        // данные не изменились - следующей ночью пересчитывать нечего: отказ виден в журнале попыток
        assertThat(scheduledRun.startNow()).isNull();
        final JsonNode attempt = Api.get("/runs/attempts").body().get("items").get(0);
        assertThat(attempt.get("outcome").asString()).isEqualTo("REJECTED");
        assertThat(attempt.get("reason").asString()).isEqualTo("RUN_ALREADY_DONE");
        assertThat(attempt.get("initiator").asString()).isEqualTo("по расписанию");
    }

    @Test
    void launchFormShowsSchedule() {
        final JsonNode schedule = Api.get("/runs/defaults").body().get("schedule");
        // в тестах расписание выключено (application.yml тестов)
        assertThat(schedule.get("enabled").asBoolean()).isFalse();
        assertThat(schedule.get("cron").asString()).isEqualTo("0 0 3 * * *");
        assertThat(schedule.get("zone").asString()).isEqualTo("Europe/Moscow");
        assertThat(schedule.get("nextAt").isNull()).isTrue();
    }

    private static JsonNode awaitFinished(final long runId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            final JsonNode run = Api.get("/runs/" + runId).body();
            if (!"RUNNING".equals(run.get("status").asString())) {
                return run;
            }
            Thread.sleep(POLL_MS);
        }
        throw new AssertionError("Прогон " + runId + " не завершился");
    }
}
