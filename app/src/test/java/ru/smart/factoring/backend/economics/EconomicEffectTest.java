package ru.smart.factoring.backend.economics;

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
 * Экономический эффект по прогону (Р-76): при ставках прогона - те же числа, что в итогах шага 5; при других ставках -
 * пересчёт тем же расчётом п. 5.1.
 */
class EconomicEffectTest extends AbstractSmartFactoringBackendTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JsonNode run;

    @BeforeEach
    void setUp() throws InterruptedException {
        Fixture.importFixture(jdbcTemplate);
        run = Fixture.run(Map.of());
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void atRunRatesMatchesStepFive() {
        final JsonNode effect = Api.get("/runs/" + run.get("id").asLong() + "/economics").body();
        final JsonNode step5 = Fixture.metrics(run, 5);

        assertThat(effect.get("scenario")).isEqualTo(effect.get("run"));
        assertThat(effect.get("run").get("fundingRate").decimalValue()).isEqualByComparingTo("0.16");
        final JsonNode base = effect.get("base");
        assertThat(base.get("offers").asInt()).isEqualTo(step5.get("offers").asInt()).isEqualTo(12);
        assertThat(base.get("belowMinIncome").asInt()).isZero();
        assertThat(base.get("portfolioEad").decimalValue()).isEqualByComparingTo(step5.get("eadTotal").decimalValue());
        assertThat(base.get("portfolioIncome").decimalValue()).isEqualByComparingTo(step5.get("incomeTotal").decimalValue());
        assertThat(effect.get("result")).isEqualTo(base);
        // доход банка = процентный доход − фондирование − ожидаемые потери − опер. расходы
        assertThat(base.get("revenue").decimalValue().subtract(base.get("funding").decimalValue())
                .subtract(base.get("expectedLoss").decimalValue()).subtract(base.get("opex").decimalValue()))
                .isCloseTo(base.get("income").decimalValue(), org.assertj.core.data.Offset.offset(new BigDecimal("0.05")));
        assertThat(effect.get("clients").asInt()).isEqualTo(run.get("comparison").get("clients").asInt());
    }

    @Test
    void scenarioRepricesOffers() {
        final long runId = run.get("id").asLong();
        final JsonNode base = Api.get("/runs/" + runId + "/economics").body().get("result");
        final JsonNode dearer = Api.get("/runs/" + runId + "/economics?fundingRate=0.18").body().get("result");
        // 22% + опер. расходы 1,5% + маржа 3% выше потолка 24%: доход по каждому предложению ниже минимального, все выпадают
        final JsonNode tooDear = Api.get("/runs/" + runId + "/economics?fundingRate=0.22").body().get("result");
        final JsonNode keener = Api.get("/runs/" + runId + "/economics?trBase=0.14").body().get("result");

        // дороже фондирование - больше расходов на него и не больше дохода банка (цена упирается в потолок)
        assertThat(dearer.get("funding").decimalValue()).isGreaterThan(base.get("funding").decimalValue());
        assertThat(dearer.get("income").decimalValue()).isLessThanOrEqualTo(base.get("income").decimalValue());
        assertThat(dearer.get("offers").asInt() + dearer.get("belowMinIncome").asInt()).isEqualTo(base.get("offers").asInt());
        assertThat(tooDear.get("belowMinIncome").asInt()).isEqualTo(base.get("offers").asInt());
        assertThat(tooDear.get("income").decimalValue()).isZero();
        // TR вдвое выше - вдвое больше ожидаемых сделок (TR одного предложения не больше 1)
        assertThat(keener.get("expectedDeals").decimalValue())
                .isCloseTo(base.get("expectedDeals").decimalValue().multiply(BigDecimal.TWO), org.assertj.core.data.Offset.offset(
                        new BigDecimal("0.001")));
        assertThat(keener.get("portfolioIncome").decimalValue()).isEqualByComparingTo(base.get("portfolioIncome").decimalValue());
    }

    @Test
    void scalesAndPayback() {
        final JsonNode effect = Api.get("/runs/" + run.get("id").asLong() + "/economics").body();

        assertThat(effect.get("scales")).hasSize(3);
        assertThat(effect.get("scales").get(1).get("code").asString()).isEqualTo("CORE");
        assertThat(effect.get("scales").get(1).get("clients").asInt()).isEqualTo(5_759);
        assertThat(effect.get("scales").get(2).get("clients").asInt()).isEqualTo(22_641);
        assertThat(effect.get("year").get("turnover").decimalValue()).isPositive();
        assertThat(effect.get("time").get("savedHoursPerYear").decimalValue()).isPositive();
    }

    @Test
    void rejectsRateOutsideZeroToOne() {
        final Api.Response response = Api.get("/runs/" + run.get("id").asLong() + "/economics?fundingRate=16");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("message").asString()).contains("fundingRate");
    }
}
