package ru.smart.factoring.backend.decision;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Модуль 7: блок 5 на фикстуре против эталона docs/claude/module-0/reference-sim/block_5.py (CASES.md).
 */
class DecisionTest extends AbstractSmartFactoringBackendTest {
    private static final Offset<BigDecimal> RUBLE = Offset.offset(new BigDecimal("1"));

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
    void decidesLikeReference() throws InterruptedException {
        final JsonNode run = Fixture.run(Map.of());
        final long runId = run.get("id").asLong();
        assertThat(run.get("status").asString()).isEqualTo("DONE");

        // предложения: номер → сумма финансирования, доход банка после деления лимита
        final Map<String, String[]> offers = Map.ofEntries(
                Map.entry("U-1003", new String[]{"4500000", "8877"}),
                Map.entry("U-1004", new String[]{"4500000", "14795"}),
                Map.entry("U-1104", new String[]{"2550000", "6078"}),
                Map.entry("U-2102", new String[]{"4250000", "11877"}),
                Map.entry("U-2103", new String[]{"5100000", "18444"}),
                Map.entry("U-2104", new String[]{"2125000", "9432"}),
                Map.entry("U-3002", new String[]{"3750000", "12329"}),
                Map.entry("U-3102", new String[]{"3400000", "6986"}),
                Map.entry("U-3201", new String[]{"2550000", "6078"}),
                Map.entry("U-4001", new String[]{"2500000", "5959"}),
                Map.entry("U-6001", new String[]{"3750000", "16644"}),
                Map.entry("U-6102", new String[]{"3000000", "6164"}));
        offers.forEach((number, expected) -> {
            final Map<String, Object> row = decision(runId, number);
            assertThat(row.get("decision")).as(number).isEqualTo("OFFER");
            assertThat((BigDecimal) row.get("ead_final")).as(number).isEqualByComparingTo(expected[0]);
            assertThat((BigDecimal) row.get("income_final")).as(number).isCloseTo(new BigDecimal(expected[1]), RUBLE);
        });

        final Map<String, String> noOffers = Map.ofEntries(
                Map.entry("U-1005", "OVERDUE_90_PROBABLE"), Map.entry("U-1102", "UPD_OVERDUE"), Map.entry("U-1201", "STOP_FACTOR"),
                Map.entry("U-1301", "NO_BUYER_DATA"), Map.entry("U-2101", "NOT_IN_TOP_N"), Map.entry("U-2301", "STOP_FACTOR"),
                Map.entry("U-3302", "RISK_LEVEL_D"), Map.entry("U-4002", "UPD_OVERDUE"), Map.entry("U-4003", "UPD_OVERDUE"),
                Map.entry("U-4004", "UPD_OVERDUE"), Map.entry("U-4101", "NO_PAYMENT_HISTORY"), Map.entry("U-5001", "LIMIT_EXHAUSTED"),
                Map.entry("U-5002", "SMALL_AMOUNT"), Map.entry("U-5003", "SHORT_TERM"), Map.entry("U-5004", "INCOME_BELOW_MIN"),
                Map.entry("U-6002", "DELAY_PROBABLE_LEVEL_C"));
        noOffers.forEach((number, reason) -> assertThat(decision(runId, number).get("reason")).as(number).isEqualTo(reason));

        // К-1: цена и ставка; К-12: уменьшено до остатка лимита; К-9: аванс на ступень ниже
        assertThat((BigDecimal) decision(runId, "U-1004").get("price_final")).isCloseTo(new BigDecimal("102082"), RUBLE);
        assertThat((Boolean) decision(runId, "U-4001").get("reduced_to_limit")).isTrue();
        assertThat((BigDecimal) decision(runId, "U-6102").get("advance_rate")).isEqualByComparingTo("0.75");

        final JsonNode metrics = Fixture.metrics(run, 5);
        assertThat(metrics.get("offers").asInt()).isEqualTo(12);
        assertThat(new BigDecimal(metrics.get("eadTotal").asString())).isEqualByComparingTo("41975000");
        assertThat(metrics.get("newOffers").asInt()).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject("select count(*) from offer where status = 'QUEUED'", Integer.class)).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject("select count(*) from offer where message_draft is null", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from notification where type = 'NEW_OFFER'", Integer.class)).isEqualTo(12);

        // Р-57: прогресс долгих частей шагов - тексты карточек (шаг 5) и модели (шаг 4) досчитаны до конца
        final JsonNode texts = run.get("steps").get(4).get("progress");
        assertThat(texts.get("label").asString()).isEqualTo("Тексты карточек (stub)");
        assertThat(texts.get("done").asInt()).isEqualTo(12);
        assertThat(texts.get("total").asInt()).isEqualTo(12);
        assertThat(texts.get("since").isNull()).isFalse();
        final JsonNode delay = run.get("steps").get(3).get("progress");
        assertThat(delay.get("label").asString()).isEqualTo("Модель задержки по УПД");
        assertThat(delay.get("done").asInt()).isEqualTo(delay.get("total").asInt()).isPositive();
        assertThat(run.get("steps").get(0).get("progress").isNull()).isTrue();
    }

    @Test
    void repeatedRunUpdatesOffersWithoutDuplicates() throws InterruptedException {
        Fixture.run(Map.of());
        final JsonNode second = Fixture.run(Map.of());

        assertThat(Fixture.metrics(second, 5).get("newOffers").asInt()).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from offer", Integer.class)).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject("select count(*) from offer where is_new", Integer.class)).isZero();
    }

    @Test
    void revokesOffersWhenUpdBecomesOverdue() throws InterruptedException {
        Fixture.run(Map.of());
        // К-15: вперёд на 25.11 - U-1003 и U-1104 в просрочке, U-1004 меньше 10 дней до оплаты
        final JsonNode forward = Fixture.run(Map.of("calcDate", "2026-11-25"));

        assertThat(offerStatus("U-1104")).isEqualTo("REVOKED UPD_OVERDUE");
        assertThat(offerStatus("U-1003")).isEqualTo("REVOKED UPD_OVERDUE");
        assertThat(offerStatus("U-1004")).isEqualTo("REVOKED NO_LONGER_ELIGIBLE");
        assertThat(Fixture.metrics(forward, 5).get("revokedOffers").asInt()).isGreaterThanOrEqualTo(3);
        // напоминания: предложения в очереди больше 3 дней по датам расчёта
        assertThat(Fixture.metrics(forward, 5).get("reminders").asInt()).isPositive();
    }

    private Map<String, Object> decision(final long runId, final String number) {
        return jdbcTemplate.queryForMap("""
                select d.* from upd_decision d join upd u on u.id = d.upd_id
                where d.run_id = ? and u.number = ?""", runId, number);
    }

    private String offerStatus(final String number) {
        return jdbcTemplate.queryForObject("""
                select o.status || ' ' || coalesce(o.revoke_reason, '-') from offer o join upd u on u.id = o.upd_id
                where u.number = ? order by o.id desc limit 1""", String.class, number);
    }
}
