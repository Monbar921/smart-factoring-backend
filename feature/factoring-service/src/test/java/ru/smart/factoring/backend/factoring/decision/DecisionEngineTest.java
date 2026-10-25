package ru.smart.factoring.backend.factoring.decision;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.factoring.model.ClientInfo;
import ru.smart.factoring.backend.factoring.model.Upd;
import ru.smart.factoring.backend.factoring.model.UpdState;
import ru.smart.factoring.backend.factoring.parameter.RunParameters;
import ru.smart.factoring.backend.factoring.scoring.ScoringResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила, отбор и деление лимита блока 5 (п. 5.2, 5.3, пример деления лимита BRD).
 */
class DecisionEngineTest {
    private static final RunParameters PARAMETERS = TestParameters.defaults();
    private static final Offset<BigDecimal> RUBLE = Offset.offset(new BigDecimal("1"));
    private static final long BUYER = 100;
    private static final LocalDate CALC = LocalDate.parse("2026-10-24");

    @Test
    void dividesBuyerLimitByRankLikeBrdExample() {
        // покупатель B, лимит 20 млн, передано 6 млн → остаток 14 млн; срок 40 + 6 = 46 дней
        final List<DecisionEngine.Candidate> candidates = List.of(
                candidate(1, client(1, "0.30"), "9411764.71", 40, buyer("0.03"), score("0.10", "0.01", 6)),
                candidate(2, client(2, "0.10"), "5000000.00", 40, buyer("0.03"), score("0.10", "0.01", 6)),
                candidate(3, client(3, "0.30"), "5882352.94", 40, buyer("0.03"), score("0.10", "0.01", 6)),
                candidate(4, client(4, "0.30"), "3529411.76", 40, buyer("0.03"), score("0.10", "0.01", 6)));

        final Map<Long, DecisionEngine.Decision> decisions = byUpd(DecisionEngine.decide(candidates,
                Map.of(BUYER, new BigDecimal("14000000")), PARAMETERS));

        assertThat(decisions.get(1L).finalEconomics().ead()).isEqualByComparingTo("8000000");
        assertThat(decisions.get(1L).finalEconomics().income()).isCloseTo(new BigDecimal("30247"), RUBLE);
        assertThat(decisions.get(2L).finalEconomics().ead()).isEqualByComparingTo("4250000");
        assertThat(decisions.get(2L).limitOrder()).isEqualTo(2);
        assertThat(decisions.get(3L).finalEconomics().ead()).isEqualByComparingTo("1750000");
        assertThat(decisions.get(3L).finalEconomics().income()).isCloseTo(new BigDecimal("6616"), RUBLE);
        assertThat(decisions.get(3L).calculated().income()).isCloseTo(new BigDecimal("18904"), RUBLE);
        assertThat(decisions.get(3L).reducedToLimit()).isTrue();
        assertThat(decisions.get(3L).offer()).isTrue();
        assertThat(decisions.get(4L).reason()).isEqualTo(NoOfferReason.LIMIT_EXHAUSTED);
    }

    @Test
    void keepsTopThreePerClientBeforeLimit() {
        final ClientInfo client = client(1, "0.30");
        final List<DecisionEngine.Candidate> candidates = List.of(
                candidate(1, client, "4000000", 16, buyer("0.02"), score("0.10", "0.01", 3)),
                candidate(2, client, "5000000", 31, buyer("0.02"), score("0.10", "0.01", 3)),
                candidate(3, client, "6000000", 41, buyer("0.02"), score("0.10", "0.01", 3)),
                candidate(4, client, "2500000", 51, buyer("0.02"), score("0.10", "0.01", 3)));

        final Map<Long, DecisionEngine.Decision> decisions = byUpd(DecisionEngine.decide(candidates,
                Map.of(BUYER, new BigDecimal("20000000")), PARAMETERS));

        assertThat(decisions.get(1L).reason()).isEqualTo(NoOfferReason.NOT_IN_TOP_N);
        assertThat(decisions.get(1L).clientRank()).isEqualTo(4);
        assertThat(decisions.values().stream().filter(DecisionEngine.Decision::offer).count()).isEqualTo(3);
    }

    @Test
    void appliesRulesInOrder() {
        final ClientInfo client = client(1, "0.30");
        final Map<Long, BigDecimal> limit = Map.of(BUYER, new BigDecimal("10000000"));

        assertThat(reason(candidate(1, client, "1000000", -5, buyer("0.02"), null), limit)).isEqualTo(NoOfferReason.UPD_OVERDUE);
        assertThat(reason(new DecisionEngine.Candidate(state(1, client, "1000000", 20), client, buyer("0.02"),
                score("0.1", "0.01", 3), true), limit)).isEqualTo(NoOfferReason.ALREADY_DECIDED);
        assertThat(reason(candidate(1, client, "1000000", 20, null, null), limit)).isEqualTo(NoOfferReason.NO_BUYER_DATA);
        assertThat(reason(candidate(1, client, "1000000", 20, buyer("0.02"),
                new ScoringResult.UpdScore(1, "NO_PAYMENT_HISTORY", null, null, null, false, "v", 1, null)), limit))
                .isEqualTo(NoOfferReason.NO_PAYMENT_HISTORY);
        assertThat(reason(candidate(1, client, "1000000", 20, buyer("0.02"),
                new ScoringResult.UpdScore(1, "ERROR", null, null, null, false, "v", 3, "нет ответа")), limit))
                .isEqualTo(NoOfferReason.SCORING_ERROR);
        assertThat(reason(candidate(1, client, "1000000", 20, buyer("0.12"), score("0.1", "0.01", 3)), limit))
                .isEqualTo(NoOfferReason.RISK_LEVEL_D);
        assertThat(reason(candidate(1, client, "1000000", 20, buyer("0.02"), score("0.1", "0.25", 3)), limit))
                .isEqualTo(NoOfferReason.OVERDUE_90_PROBABLE);
        assertThat(reason(candidate(1, client, "1000000", 20, buyer("0.02"), score("0.1", "0.01", 3)), Map.of(BUYER, BigDecimal.ZERO)))
                .isEqualTo(NoOfferReason.LIMIT_EXHAUSTED);
        assertThat(reason(candidate(1, client, "1000000", 20, buyer("0.05"), score("0.5", "0.01", 3)), limit))
                .isEqualTo(NoOfferReason.DELAY_PROBABLE_LEVEL_C);
        assertThat(reason(candidate(1, client, "250000", 20, buyer("0.02"), score("0.1", "0.01", 3)), limit))
                .isEqualTo(NoOfferReason.SMALL_AMOUNT);
        assertThat(reason(candidate(1, client, "1000000", 4, buyer("0.02"), score("0.1", "0.01", 3)), limit))
                .isEqualTo(NoOfferReason.SHORT_TERM);
        assertThat(reason(candidate(1, client, "600000", 18, buyer("0.05"), score("0.1", "0.01", 3)), limit))
                .isEqualTo(NoOfferReason.INCOME_BELOW_MIN);
    }

    @Test
    void lowersAdvanceWhenDelayProbable() {
        final ClientInfo client = client(1, "0.30");
        final DecisionEngine.Decision decision = DecisionEngine.decide(
                List.of(candidate(1, client, "4000000", 16, buyer("0.02"), score("0.45", "0.02", 9))),
                Map.of(BUYER, new BigDecimal("20000000")), PARAMETERS).getFirst();

        assertThat(decision.offer()).isTrue();
        assertThat(decision.delayProbable()).isTrue();
        assertThat(decision.advanceRate()).isEqualByComparingTo("0.75");
        assertThat(decision.finalEconomics().ead()).isEqualByComparingTo("3000000");
        assertThat(decision.finalEconomics().termDays()).isEqualTo(25);
        // LGD по фактическому уровню B, а не по ступени ниже
        assertThat(decision.finalEconomics().lgd()).isEqualByComparingTo("0.45");
    }

    @Test
    void takeRateGrowsWithLowLiquidityAndLongDeferral() {
        assertThat(DecisionEngine.takeRate(candidate(1, client(1, "0.30"), "1000000", 20, buyer("0.02"), null), PARAMETERS))
                .isEqualByComparingTo("0.07");
        assertThat(DecisionEngine.takeRate(candidate(1, client(1, "0.10"), "1000000", 20, buyer("0.02"), null), PARAMETERS))
                .isEqualByComparingTo("0.105");
        assertThat(DecisionEngine.takeRate(new DecisionEngine.Candidate(state(1, client(1, "0.10"), "1000000", 20, 60),
                client(1, "0.10"), buyer("0.02"), null, false), PARAMETERS)).isEqualByComparingTo("0.1575");
    }

    private static NoOfferReason reason(final DecisionEngine.Candidate candidate, final Map<Long, BigDecimal> limit) {
        return DecisionEngine.decide(List.of(candidate), limit, PARAMETERS).getFirst().reason();
    }

    private static Map<Long, DecisionEngine.Decision> byUpd(final List<DecisionEngine.Decision> decisions) {
        return decisions.stream().collect(Collectors.toMap(decision -> decision.state().upd().id(), Function.identity()));
    }

    private static DecisionEngine.Candidate candidate(final long id, final ClientInfo client, final String outstanding,
                                                      final int daysToDue, final ScoringResult.BuyerScore buyer,
                                                      final ScoringResult.UpdScore score) {
        return new DecisionEngine.Candidate(state(id, client, outstanding, daysToDue), client, buyer, score, false);
    }

    private static UpdState state(final long id, final ClientInfo client, final String outstanding, final int daysToDue) {
        return state(id, client, outstanding, daysToDue, 30);
    }

    private static UpdState state(final long id, final ClientInfo client, final String outstanding, final int daysToDue,
                                  final int deferralDays) {
        final BigDecimal amount = new BigDecimal(outstanding);
        final Upd upd = new Upd(id, client.id(), BUYER, "7720000002", 1L, "Д-1", null, "U-" + id,
                CALC.plusDays(daysToDue).minusDays(deferralDays), amount, deferralDays);
        final Integer category = daysToDue >= 0 ? (daysToDue <= 30 ? 1 : 2) : 8;
        return new UpdState(upd, upd.plannedDueDate(), daysToDue, BigDecimal.ZERO, amount, UpdState.PaymentStatus.UNPAID, null, null,
                category);
    }

    private static ClientInfo client(final long id, final String liquidity) {
        return new ClientInfo(id, "77100000" + id, "Клиент " + id, "TRADE", new BigDecimal(liquidity), 1);
    }

    private static ScoringResult.BuyerScore buyer(final String pd) {
        final BigDecimal value = new BigDecimal(pd);
        return new ScoringResult.BuyerScore(BUYER, "7720000002", null, true, value, "MODEL", false, PARAMETERS.riskLevel(value),
                List.of(), "v", CALC, false, null);
    }

    private static ScoringResult.UpdScore score(final String pDelay, final String p90, final int delayDays) {
        return new ScoringResult.UpdScore(1, "SCORED", new BigDecimal(pDelay), new BigDecimal(p90), new BigDecimal(delayDays), false,
                "v", 1, null);
    }
}
