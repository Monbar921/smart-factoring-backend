package ru.smart.factoring.backend.categorization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Модули 3-4: блоки 2-3 на фикстуре против эталона docs/claude/module-0/reference-sim (CASES.md: К-5…К-8, К-15).
 */
class ReconciliationAndCategorizationTest extends AbstractSmartFactoringBackendTest {

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
    void reconcilesPaymentsLikeReference() throws InterruptedException {
        final long runId = Fixture.run(Map.of()).get("id").asLong();

        // К-5
        assertThat(allocations(runId, "P-0002")).containsExactly("U-1002 2000000.00 CONTRACT_FIFO");
        assertThat(allocations(runId, "P-0005")).containsExactly("U-1102 1500000.00 BUYER_FIFO");
        assertThat(allocations(runId, "P-0006")).containsExactly("U-1103 800000.00 UPD_NUMBER", "U-1102 200000.00 CARRY_CONTRACT");
        // К-6
        assertThat(allocations(runId, "P-0007")).containsExactly("- 500000.00 UNALLOCATED");
    }

    @Test
    void categorizesLikeReference() throws InterruptedException {
        final JsonNode run = Fixture.run(Map.of());
        final long runId = run.get("id").asLong();

        assertThat(state(runId, "U-1003")).isEqualTo("PARTIAL 3 5000000.00");
        assertThat(state(runId, "U-1004")).isEqualTo("UNPAID 2 5000000.00");
        assertThat(state(runId, "U-1102")).isEqualTo("PARTIAL 6 300000.00");
        assertThat(state(runId, "U-4002")).isEqualTo("PARTIAL 5 1200000.00");
        assertThat(state(runId, "U-4003")).isEqualTo("UNPAID 8 2000000.00");
        assertThat(state(runId, "U-4004")).isEqualTo("UNPAID 7 1000000.00");
        assertThat(state(runId, "U-1001")).isEqualTo("PAID null 0.00");
        assertThat(jdbcTemplate.queryForObject("select delay_days from upd_state us join upd u on u.id = us.upd_id where u.number = 'U-6101'",
                Integer.class)).isEqualTo(30);

        final JsonNode step2 = Fixture.metrics(run, 2);
        assertThat(step2.get("paymentsUnallocated").asInt()).isEqualTo(1);
        assertThat(step2.get("updsClosed").asInt()).isEqualTo(9);
        final JsonNode step3 = Fixture.metrics(run, 3);
        assertThat(step3.get("openUpds").asInt()).isEqualTo(28);
        assertThat(step3.get("overdueNotifications").asInt()).isEqualTo(4);
        assertThat(step3.get("riskSignals").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select buyer_inn from demo_risk_signal", String.class)).isEqualTo("7720000006");
        assertThat(jdbcTemplate.queryForObject("select count(*) from notification where type = 'OVERDUE'", Integer.class)).isEqualTo(4);
    }

    @Test
    void movesWithCalcDate() throws InterruptedException {
        // К-15 вперёд: U-1104 и U-1003 уходят в просрочку
        final long forward = Fixture.run(Map.of("calcDate", "2026-11-25")).get("id").asLong();
        assertThat(state(forward, "U-1104")).startsWith("UNPAID 8");
        assertThat(state(forward, "U-1003")).startsWith("PARTIAL 6");

        // К-15 назад: платёж P-0006 от 22.10 не учитывается, U-1103 снова открыт
        final long back = Fixture.run(Map.of("calcDate", "2026-10-21")).get("id").asLong();
        assertThat(allocations(back, "P-0006")).isEmpty();
        assertThat(state(back, "U-1103")).isEqualTo("UNPAID 1 800000.00");
        assertThat(state(back, "U-1102")).isEqualTo("PARTIAL 6 500000.00");
    }

    private List<String> allocations(final long runId, final String sourceId) {
        return jdbcTemplate.queryForList("""
                select coalesce(u.number, '-') || ' ' || a.amount || ' ' || a.rule
                from payment_allocation a
                join payment p on p.id = a.payment_id
                left join upd u on u.id = a.upd_id
                where a.run_id = ? and p.source_id = ?
                order by a.id""", String.class, runId, sourceId);
    }

    private String state(final long runId, final String number) {
        return jdbcTemplate.queryForObject("""
                select us.payment_status || ' ' || coalesce(us.category::text, 'null') || ' ' || us.outstanding
                from upd_state us join upd u on u.id = us.upd_id
                where us.run_id = ? and u.number = ?""", String.class, runId, number);
    }
}
