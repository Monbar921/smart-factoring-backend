package ru.smart.factoring.backend.crm;

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

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Модуль 9: блок 6, эмулятор CRM и детализация прогона (ЭП-4, ЭП-5, ЭП-7, п. 5.5, К-16).
 */
class CrmAndDetailsTest extends AbstractSmartFactoringBackendTest {

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
    void managerDecisionsSurviveNextRun() throws InterruptedException {
        Fixture.run(Map.of());
        final long managerId = Api.get("/managers").body().get(0).get("id").asLong();
        final JsonNode queue = Api.get("/managers/" + managerId + "/queue").body();
        assertThat(queue).hasSize(12);
        // очередь по убыванию ранга
        assertThat(queue.get(0).get("rank").decimalValue()).isGreaterThanOrEqualTo(queue.get(1).get("rank").decimalValue());

        final long transferred = offerId(queue, "U-1004");
        final long rejected = offerId(queue, "U-1104");
        final Api.Response transfer = Api.post("/offers/" + transferred + "/transfer", null);
        assertThat(transfer.status()).isEqualTo(200);
        assertThat(transfer.body().get("offer").get("status").asString()).isEqualTo("TRANSFERRED");
        assertThat(transfer.body().get("packageId").asLong()).isPositive();
        assertThat(Api.post("/offers/" + rejected + "/reject", Map.of("comment", "клиент не заинтересован")).status()).isEqualTo(200);
        // повторное решение по закрытому предложению - 409
        assertThat(Api.post("/offers/" + transferred + "/reject", null).status()).isEqualTo(409);

        // К-16: повторный прогон - без дублей, решения не возвращаются, остаток лимита B1 уменьшен на переданное
        final JsonNode second = Fixture.run(Map.of());
        final long runId = second.get("id").asLong();
        assertThat(Api.get("/managers/" + managerId + "/queue").body()).hasSize(10);
        assertThat(jdbcTemplate.queryForObject("select count(*) from offer", Integer.class)).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject("""
                select d.reason from upd_decision d join upd u on u.id = d.upd_id where d.run_id = ? and u.number = 'U-1104'""",
                String.class, runId)).isEqualTo("ALREADY_DECIDED");
        assertThat(jdbcTemplate.queryForObject("""
                select d.limit_available_before from upd_decision d join upd u on u.id = d.upd_id
                where d.run_id = ? and u.number = 'U-1003'""", BigDecimal.class, runId)).isEqualByComparingTo("25500000");
        assertThat(jdbcTemplate.queryForObject("select count(*) from demo_factoring_package", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from run_log where event_code in ('OFFER_TRANSFERRED', 'OFFER_REJECTED')",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void showsClientCardAndNotifications() throws InterruptedException {
        Fixture.run(Map.of());
        final long c1 = clientId("7710000001");
        final long c4 = clientId("7710000004");

        final JsonNode card = Api.get("/clients/" + c1 + "/card").body();
        assertThat(card.get("offers")).hasSize(3);
        assertThat(card.get("offers").get(0).get("messageDraft").asString()).contains("УПД №");
        assertThat(card.get("offers").get(0).get("reasons")).isNotEmpty();
        assertThat(card.get("summary").get("overdue").decimalValue()).isEqualByComparingTo("300000");
        assertThat(card.get("summary").get("cashGap").isNull()).isTrue();
        assertThat(card.get("upds").toString()).contains("OVERDUE_90_PROBABLE", "STOP_FACTOR", "NO_BUYER_DATA");

        // кассовый разрыв: просрочка при ликвидности ниже 0,2 (Р-15)
        final JsonNode card4 = Api.get("/clients/" + c4 + "/card").body();
        assertThat(card4.get("summary").get("cashGap").decimalValue()).isEqualByComparingTo("4200000");

        final long managerId = Api.get("/managers").body().get(0).get("id").asLong();
        final JsonNode notifications = Api.get("/managers/" + managerId + "/notifications").body();
        assertThat(notifications.get("items").toString()).contains("OVERDUE", "RISK_SIGNAL", "NEW_OFFER");
    }

    @Test
    void showsFunnelAndUpdTrace() throws InterruptedException {
        final long runId = Fixture.run(Map.of()).get("id").asLong();

        final JsonNode funnel = Api.get("/runs/" + runId + "/funnel").body();
        assertThat(funnel.get(0).get("code").asString()).isEqualTo("OPEN");
        assertThat(funnel.get(0).get("count").asInt()).isEqualTo(28);
        assertThat(funnel.get(1).get("count").asInt()).isEqualTo(24);
        final JsonNode queued = funnel.get(funnel.size() - 1);
        assertThat(queued.get("code").asString()).isEqualTo("QUEUED");
        assertThat(queued.get("count").asInt()).isEqualTo(12);
        assertThat(queued.get("dropped").get(0).get("reason").asString()).isEqualTo("LIMIT_EXHAUSTED");

        final JsonNode dropped = Api.get("/runs/" + runId + "/upds?stage=TOP_N").body();
        assertThat(dropped.get("total").asInt()).isEqualTo(1);
        assertThat(dropped.get("items").get(0).get("number").asString()).isEqualTo("U-2101");

        final long updId = jdbcTemplate.queryForObject("select id from upd where number = 'U-1102'", Long.class);
        final JsonNode trace = Api.get("/runs/" + runId + "/upds/" + updId + "/trace").body();
        assertThat(trace.get("steps")).hasSize(5);
        assertThat(trace.get("steps").get(1).get("details").get("allocations")).hasSize(2);
        assertThat(trace.get("steps").get(2).get("summary").asString()).isEqualTo("Категория 6");

        assertThat(Api.get("/runs/" + runId + "/buyers").body()).hasSize(9);
        assertThat(Api.get("/runs/" + runId + "/clients").body()).hasSize(6);
    }

    private long offerId(final JsonNode queue, final String number) {
        for (final JsonNode offer : queue) {
            if (number.equals(offer.get("updNumber").asString())) {
                return offer.get("offerId").asLong();
            }
        }
        throw new AssertionError("Нет предложения по " + number);
    }

    private long clientId(final String inn) {
        return jdbcTemplate.queryForObject("select id from client where inn = ?", Long.class, inn);
    }
}
