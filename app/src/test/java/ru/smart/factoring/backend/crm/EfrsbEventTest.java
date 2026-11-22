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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Реакция на негативное событие (Р-86, дорожная карта п. 1.3-11): событие ЕФРСБ → предложения «отозвано» →
 * сигнал в риск → уведомление менеджеру; следующий прогон не создаёт предложение заново.
 */
class EfrsbEventTest extends AbstractSmartFactoringBackendTest {
    private static final String EVENTS = "/manual-upload/efrsb-event";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() throws InterruptedException {
        Fixture.importFixture(jdbcTemplate);
        Fixture.run(Map.of());
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void bankruptcyEventRevokesOffersSignalsRiskAndNotifiesManager() throws InterruptedException {
        final JsonNode buyer = Api.get(EVENTS + "/buyers").body().get(0);
        final String inn = buyer.get("inn").asString();
        final int queued = buyer.get("queuedOffers").asInt();
        assertThat(queued).isPositive();

        final Api.Response response = Api.post(EVENTS, Map.of("buyerInn", inn, "kind", "OBSERVATION"));
        assertThat(response.status()).isEqualTo(200);
        final JsonNode reaction = response.body();
        assertThat(reaction.get("stopFactor").asString()).isEqualTo("BANKRUPTCY");
        assertThat(reaction.get("revokedOffers").asInt()).isEqualTo(queued);
        assertThat(reaction.get("riskSignal").asBoolean()).isTrue();
        assertThat(reaction.get("notifiedManagers").asInt()).isEqualTo(1);

        // предложения покупателя - «отозвано», причина - стоп-фактор
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from offer o join buyer b on b.id = o.buyer_id
                where b.inn = ? and o.status = 'REVOKED' and o.revoke_reason = 'STOP_FACTOR'""", Integer.class, inn)).isEqualTo(queued);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from offer o join buyer b on b.id = o.buyer_id where b.inn = ? and o.status = 'QUEUED'""",
                Integer.class, inn)).isZero();
        // сигнал в риск-подразделение - вне прогона, источник ЕФРСБ
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from imitation_risk_signal where buyer_inn = ? and source = 'EFRSB' and run_id is null",
                Integer.class, inn)).isEqualTo(1);

        // менеджер видит уведомления об отзыве первыми и сигнал в риск
        final long managerId = Api.get("/managers").body().get(0).get("id").asLong();
        final JsonNode notifications = Api.get("/managers/" + managerId + "/notifications").body().get("items");
        assertThat(notifications.get(0).get("runId").isNull()).isTrue();
        final List<String> types = new ArrayList<>();
        notifications.forEach(item -> {
            if (item.get("runId").isNull()) {
                types.add(item.get("type").asString());
            }
        });
        assertThat(types).containsOnly("OFFER_REVOKED", "RISK_SIGNAL");
        assertThat(types.stream().filter("OFFER_REVOKED"::equals)).hasSize(queued);
        assertThat(notifications.get(0).get("payload").get("stopFactor").asString()).isEqualTo("BANKRUPTCY");

        // журнал вне прогона: событие, отзыв, сигнал
        assertThat(jdbcTemplate.queryForList("select event_code from run_log where run_id is null", String.class))
                .contains("EFRSB_EVENT", "OFFER_REVOKED", "RISK_SIGNAL");

        // то же событие ещё раз: отзывать нечего, сигнал не дублируется
        final JsonNode repeat = Api.post(EVENTS, Map.of("buyerInn", inn, "kind", "OBSERVATION")).body();
        assertThat(repeat.get("revokedOffers").asInt()).isZero();
        assertThat(repeat.get("riskSignal").asBoolean()).isFalse();

        // событие записано в источники: следующий анализ видит стоп-фактор и предложение заново не создаёт
        final JsonNode run = Fixture.run(Map.of());
        assertThat(run.get("status").asString()).isIn("DONE", "DONE_WITH_WARNINGS");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from offer o join buyer b on b.id = o.buyer_id where b.inn = ? and o.status = 'QUEUED'""",
                Integer.class, inn)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select stop_factor from buyer_score s join buyer b on b.id = s.buyer_id
                where b.inn = ? and s.run_id = ?""", String.class, inn, run.get("id").asLong())).isEqualTo("BANKRUPTCY");
        // уведомление об отзыве у менеджера остаётся и после ночного анализа
        assertThat(Api.get("/managers/" + managerId + "/notifications").body().get("items").toString()).contains("OFFER_REVOKED");
    }

    @Test
    void intentionIsNotStopFactor() {
        final String inn = Api.get(EVENTS + "/buyers").body().get(0).get("inn").asString();

        final JsonNode reaction = Api.post(EVENTS, Map.of("buyerInn", inn, "kind", "INTENT")).body();

        assertThat(reaction.get("stopFactor").isNull()).isTrue();
        assertThat(reaction.get("revokedOffers").asInt()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from offer o join buyer b on b.id = o.buyer_id where b.inn = ? and o.status = 'QUEUED'""",
                Integer.class, inn)).isPositive();
        assertThat(jdbcTemplate.queryForObject("select count(*) from manual_upload_efrsb_event where inn = ?", Integer.class, inn))
                .isPositive();
    }

    @Test
    void rejectsUnknownBuyerAndBadInput() {
        assertThat(Api.post(EVENTS, Map.of("buyerInn", "123", "kind", "BANKRUPT")).status()).isEqualTo(400);
        assertThat(Api.post(EVENTS, Map.of("buyerInn", "7799999999", "kind", "BANKRUPT")).status()).isEqualTo(404);
        assertThat(Api.post(EVENTS, Map.of("buyerInn", "7799999999")).status()).isEqualTo(400);
    }
}
