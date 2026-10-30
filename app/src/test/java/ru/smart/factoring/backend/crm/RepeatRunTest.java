package ru.smart.factoring.backend.crm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Повторный прогон тех же данных через «Прогнать заново» (замечания 30.10, П4-4, ПР-8): заменяет результат прежнего,
 * не дублирует предложения и не шлёт уведомлений по тем же самым предложениям.
 */
class RepeatRunTest extends AbstractSmartFactoringBackendTest {

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
    void repeatRunReplacesOffersWithoutDuplicatesAndNotifications() throws InterruptedException {
        final long first = Fixture.run(Map.of()).get("id").asLong();
        final List<String> queueAfterFirst = queue();
        assertThat(queueAfterFirst).isNotEmpty();
        assertThat(notifications(first)).isPositive();

        final long second = Fixture.run(Map.of("force", true)).get("id").asLong();

        // то же число предложений и те же суммы, по одному УПД - одно предложение, обновлённое вторым прогоном
        assertThat(queue()).isEqualTo(queueAfterFirst);
        assertThat(jdbcTemplate.queryForList("""
                select upd_id from offer where status = 'QUEUED' group by upd_id having count(*) > 1""", Long.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from offer where status = 'QUEUED' and last_run_id <> ?",
                Long.class, second)).isZero();
        assertThat(notifications(second)).isZero();
    }

    private List<String> queue() {
        return jdbcTemplate.queryForList("""
                select upd_id || ' ' || ead || ' ' || price || ' ' || term_days from offer where status = 'QUEUED' order by upd_id""",
                String.class);
    }

    private long notifications(final long runId) {
        return jdbcTemplate.queryForObject("select count(*) from notification where run_id = ?", Long.class, runId);
    }
}
