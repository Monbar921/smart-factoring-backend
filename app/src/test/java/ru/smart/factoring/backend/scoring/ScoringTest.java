package ru.smart.factoring.backend.scoring;

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
 * Модули 5-6: блок 4 на фикстуре со встроенной заглушкой моделей (CASES.md: К-2, К-3, К-4, К-9, К-10, К-18).
 */
class ScoringTest extends AbstractSmartFactoringBackendTest {

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
    void scoresBuyersAndUpds() throws InterruptedException {
        final JsonNode run = Fixture.run(Map.of());
        final long runId = run.get("id").asLong();

        assertThat(run.get("status").asString()).isEqualTo("DONE");
        assertThat(run.get("modelVersions").get("pd").asString()).isEqualTo("pd-stub-1");

        // К-2, К-3: стоп-факторы; БРИГ с прекращённым делом - не стоп-фактор
        assertThat(buyer(runId, "0225011072")).isEqualTo("BANKRUPTCY null null");
        assertThat(buyer(runId, "5507213870")).isEqualTo("EXCLUSION null null");
        assertThat(buyer(runId, "0277120989")).isEqualTo("- 0.005000 A");
        // К-4: базовый PD, «мало данных»
        assertThat(buyer(runId, "7720000005")).isEqualTo("- 0.025000 B");
        assertThat(jdbcTemplate.queryForObject("""
                select s.low_data and s.pd_source = 'BASE' from buyer_score s join buyer b on b.id = s.buyer_id
                where s.run_id = ? and b.inn = '7720000005'""", Boolean.class, runId)).isTrue();
        // К-10: уровень D
        assertThat(buyer(runId, "7720000007")).isEqualTo("- 0.120000 D");
        assertThat(buyer(runId, "7720000006")).isEqualTo("- 0.050000 C");

        // К-18: нет истории оплат у покупателя; нет истории пары - оценка по дисциплине покупателя
        assertThat(upd(runId, "U-4101")).isEqualTo("NO_PAYMENT_HISTORY null false");
        assertThat(upd(runId, "U-3201")).isEqualTo("SCORED 0.100000 true");
        // К-9: высокая вероятность задержки
        assertThat(upd(runId, "U-6102")).isEqualTo("SCORED 0.450000 false");
        // просроченные УПД моделью задержки не оцениваются
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from upd_score s join upd u on u.id = s.upd_id where s.run_id = ? and u.number = 'U-4004'""",
                Integer.class, runId)).isZero();

        final JsonNode metrics = Fixture.metrics(run, 4);
        assertThat(metrics.get("stopFactorBuyers").asInt()).isEqualTo(2);
        assertThat(metrics.get("lowDataBuyers").asInt()).isEqualTo(1);
        assertThat(metrics.get("noPaymentHistoryBuyers").asInt()).isEqualTo(1);
        assertThat(metrics.get("buyersByRiskLevel").get("A").asInt()).isEqualTo(1);
        assertThat(metrics.get("buyersByRiskLevel").get("D").asInt()).isEqualTo(1);
        assertThat(metrics.get("delayProbableUpds").asInt()).isEqualTo(2);
        assertThat(metrics.get("scoringErrors").asInt()).isZero();
        assertThat(Api.get("/runs/" + runId + "/log?stepNo=4").body().toString()).contains("NO_PAIR_HISTORY", "STOP_FACTOR", "LOW_DATA");
    }

    @Test
    void reusesPdUntilDataChanges() throws InterruptedException {
        Fixture.run(Map.of());
        final JsonNode second = Fixture.run(Map.of());

        // базовый PD «мало данных» не кешируется (Р-66): он зависит от причины из источника, а не от отчётности
        assertThat(Fixture.metrics(second, 4).get("pdFromCache").asInt()).isEqualTo(6);
    }

    private String buyer(final long runId, final String inn) {
        return jdbcTemplate.queryForObject("""
                select coalesce(s.stop_factor, '-') || ' ' || coalesce(s.pd::text, 'null') || ' ' || coalesce(s.risk_level, 'null')
                from buyer_score s join buyer b on b.id = s.buyer_id
                where s.run_id = ? and b.inn = ?""", String.class, runId, inn);
    }

    private String upd(final long runId, final String number) {
        return jdbcTemplate.queryForObject("""
                select s.status || ' ' || coalesce(s.p_delay::text, 'null') || ' ' || s.no_pair_history
                from upd_score s join upd u on u.id = s.upd_id
                where s.run_id = ? and u.number = ?""", String.class, runId, number);
    }
}
