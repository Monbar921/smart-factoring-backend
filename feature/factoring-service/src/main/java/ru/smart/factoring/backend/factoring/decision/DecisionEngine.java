package ru.smart.factoring.backend.factoring.decision;

import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.factoring.model.ClientInfo;
import ru.smart.factoring.backend.factoring.model.UpdState;
import ru.smart.factoring.backend.factoring.parameter.RunParameters;
import ru.smart.factoring.backend.factoring.scoring.ScoringResult;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Блок 5. Решение по каждому открытому УПД и отбор предложений (порядок - вводная к блоку 5). Без БД.
 * <ol>
 *     <li>правила п. 5.2 до экономики: стоп-фактор, данные, оценка, уровень D, 90+, лимит, вероятная задержка;</li>
 *     <li>доля аванса (при вероятной задержке - ступенью ниже), сумма финансирования, срок, экономика (п. 5.1);</li>
 *     <li>минимальные остаток, срок до оплаты и доход;</li>
 *     <li>ранг = TR × доход до лимита; на клиента - не больше N лучших (п. 5.3, пункт 2);</li>
 *     <li>остаток лимита покупателя делится между отобранными по убыванию ранга, при равном - по дате и номеру УПД;
 *     сумма, цена и доход пересчитываются, ранг - нет (п. 5.3, пункт 3).</li>
 * </ol>
 * Одинаковый вход даёт одинаковый результат (НФР-5).
 */
public final class DecisionEngine {
    private static final Comparator<Decision> BY_RANK = Comparator.comparing((Decision decision) -> decision.rank()).reversed()
            .thenComparing(decision -> decision.state().upd().updDate())
            .thenComparing(decision -> decision.state().upd().number())
            .thenComparingLong(decision -> decision.state().upd().id());

    private static final int RATE_SCALE = 6;

    private DecisionEngine() {
    }

    /**
     * @param candidates     открытые УПД клиентов прогона
     * @param limitRemaining остаток лимита покупателя до деления: лимит по уровню минус финансирование по переданным
     *                       в факторинг и не оплаченным УПД (п. 5.3)
     */
    public static List<Decision> decide(final List<Candidate> candidates, final Map<Long, BigDecimal> limitRemaining,
                                        final RunParameters parameters) {
        final List<Decision> decisions = new ArrayList<>();
        for (final Candidate candidate : candidates) {
            decisions.add(evaluate(candidate, limitRemaining, parameters));
        }
        final List<Decision> passed = decisions.stream().filter(decision -> decision.reason() == null).toList();

        final Map<Long, Decision> result = new LinkedHashMap<>();
        decisions.forEach(decision -> result.put(decision.state().upd().id(), decision));

        // п. 5.3, пункт 2: не больше N лучших на клиента, отбор раньше деления лимита
        final Map<Long, List<Decision>> byClient = passed.stream()
                .collect(Collectors.groupingBy(decision -> decision.client().id(), LinkedHashMap::new, Collectors.toList()));
        final List<Decision> selected = new ArrayList<>();
        byClient.values().forEach(list -> {
            final List<Decision> sorted = list.stream().sorted(BY_RANK).toList();
            for (int i = 0; i < sorted.size(); i++) {
                final Decision decision = sorted.get(i).withClientRank(i + 1);
                if (i < parameters.offersPerClient()) {
                    selected.add(decision);
                    result.put(decision.state().upd().id(), decision);
                } else {
                    result.put(decision.state().upd().id(), decision.withReason(NoOfferReason.NOT_IN_TOP_N));
                }
            }
        });

        // п. 5.3, пункт 3: деление остатка лимита покупателя между отобранными предложениями всех клиентов
        final Map<Long, BigDecimal> left = new HashMap<>(limitRemaining);
        final List<Decision> ordered = selected.stream().sorted(BY_RANK).toList();
        for (int i = 0; i < ordered.size(); i++) {
            final Decision decision = ordered.get(i);
            final long buyerId = decision.state().upd().buyerId();
            final BigDecimal available = left.getOrDefault(buyerId, BigDecimal.ZERO);
            Decision updated = decision.withLimit(i + 1, available);
            if (available.signum() <= 0) {
                updated = updated.withReason(NoOfferReason.LIMIT_EXHAUSTED);
            } else {
                final BigDecimal ead = decision.calculated().ead().min(available);
                final boolean reduced = ead.compareTo(decision.calculated().ead()) < 0;
                final Economics.Result finalEconomics = reduced
                        ? Economics.calculate(ead, decision.calculated().termDays(), decision.buyer().pd(),
                        decision.buyer().riskLevel(), parameters)
                        : decision.calculated();
                left.put(buyerId, available.subtract(ead));
                updated = updated.withFinal(finalEconomics, reduced);
                if (finalEconomics.income().compareTo(parameters.minIncome()) < 0) {
                    updated = updated.withReason(NoOfferReason.INCOME_BELOW_MIN);
                }
            }
            result.put(updated.state().upd().id(), updated);
        }
        return new ArrayList<>(result.values());
    }

    private static Decision evaluate(final Candidate candidate, final Map<Long, BigDecimal> limitRemaining,
                                     final RunParameters parameters) {
        final Decision empty = new Decision(candidate.state(), candidate.client(), candidate.buyer(), candidate.score(), null,
                null, false, null, null, null, null, null, 0, null, false);
        final UpdState state = candidate.state();
        final ScoringResult.BuyerScore buyer = candidate.buyer();
        final ScoringResult.UpdScore score = candidate.score();
        if (state.overdue()) {
            return empty.withReason(NoOfferReason.UPD_OVERDUE);
        }
        if (candidate.alreadyDecided()) {
            return empty.withReason(NoOfferReason.ALREADY_DECIDED);
        }
        if (buyer == null) {
            return empty.withReason(NoOfferReason.NO_BUYER_DATA);
        }
        if (buyer.stopFactor() != null) {
            return empty.withReason(NoOfferReason.STOP_FACTOR);
        }
        if (score != null && "NO_PAYMENT_HISTORY".equals(score.status())) {
            return empty.withReason(NoOfferReason.NO_PAYMENT_HISTORY);
        }
        if (buyer.pd() == null || score == null || !score.scored()) {
            return empty.withReason(NoOfferReason.SCORING_ERROR);
        }
        final RiskLevel level = buyer.riskLevel();
        if (level == RiskLevel.D) {
            return empty.withReason(NoOfferReason.RISK_LEVEL_D);
        }
        if (score.pOverdue90().compareTo(parameters.overdue90ProbabilityThreshold()) > 0) {
            return empty.withReason(NoOfferReason.OVERDUE_90_PROBABLE);
        }
        if (limitRemaining.getOrDefault(state.upd().buyerId(), BigDecimal.ZERO).signum() <= 0) {
            return empty.withReason(NoOfferReason.LIMIT_EXHAUSTED);
        }
        final boolean delayProbable = score.pDelay().compareTo(parameters.delayProbabilityThreshold()) > 0;
        if (delayProbable && level == RiskLevel.C) {
            return empty.withDelay(true).withReason(NoOfferReason.DELAY_PROBABLE_LEVEL_C);
        }
        if (state.outstanding().compareTo(parameters.minOutstanding()) < 0) {
            return empty.withDelay(delayProbable).withReason(NoOfferReason.SMALL_AMOUNT);
        }
        if (state.daysToDue() < parameters.minDaysToDue()) {
            return empty.withDelay(delayProbable).withReason(NoOfferReason.SHORT_TERM);
        }
        // «ступень ниже» меняет только долю аванса: LGD и лимит - по фактическому уровню (п. 5.1)
        final BigDecimal advance = parameters.advanceRate().get(delayProbable ? lower(level) : level);
        final int term = state.daysToDue() + score.expectedDelayDays().setScale(0, RoundingMode.HALF_UP).intValueExact();
        final Economics.Result economics = Economics.calculate(Economics.money(state.outstanding().multiply(advance)), term,
                buyer.pd(), level, parameters);
        final BigDecimal tr = takeRate(candidate, parameters);
        final Decision decision = new Decision(state, candidate.client(), buyer, score, null, advance, delayProbable, economics, null,
                tr, Economics.money(tr.multiply(economics.income())), null, 0, null, false);
        if (economics.income().compareTo(parameters.minIncome()) < 0) {
            return decision.withReason(NoOfferReason.INCOME_BELOW_MIN);
        }
        return decision;
    }

    /**
     * TR (п. 5.3): база × коэффициент за низкую ликвидность клиента × коэффициент за долгую отсрочку по договору.
     */
    static BigDecimal takeRate(final Candidate candidate, final RunParameters parameters) {
        BigDecimal tr = parameters.trBase();
        final BigDecimal liquidity = candidate.client().absLiquidity();
        if (liquidity != null && liquidity.compareTo(parameters.trLiquidityThreshold()) < 0) {
            tr = tr.multiply(parameters.trMultiplier());
        }
        if (candidate.state().upd().deferralDays() >= parameters.trDeferralDaysThreshold()) {
            tr = tr.multiply(parameters.trMultiplier());
        }
        return tr.round(MathContext.DECIMAL64).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static RiskLevel lower(final RiskLevel level) {
        return switch (level) {
            case A -> RiskLevel.B;
            case B -> RiskLevel.C;
            default -> throw new IllegalArgumentException("Нет ступени ниже уровня " + level);
        };
    }

    /**
     * Вход: открытый УПД с клиентом и оценками блока 4.
     *
     * @param buyer          null - покупателя нет в реестре
     * @param score          null - модель задержки не применялась
     * @param alreadyDecided по УПД уже есть решение менеджера (п. 6.4)
     */
    public record Candidate(UpdState state, ClientInfo client, ScoringResult.BuyerScore buyer, ScoringResult.UpdScore score,
                            boolean alreadyDecided) {
    }

    /**
     * Решение по УПД (upd_decision).
     *
     * @param reason         null - предложение есть
     * @param calculated     экономика до деления лимита
     * @param finalEconomics экономика после деления лимита; null - до деления не дошло
     * @param limitAvailable остаток лимита покупателя перед этим предложением
     */
    public record Decision(UpdState state, ClientInfo client, ScoringResult.BuyerScore buyer, ScoringResult.UpdScore score,
                           NoOfferReason reason, BigDecimal advanceRate, boolean delayProbable, Economics.Result calculated,
                           Economics.Result finalEconomics, BigDecimal tr, BigDecimal rank, Integer clientRank, int limitOrder,
                           BigDecimal limitAvailable, boolean reducedToLimit) {

        public boolean offer() {
            return reason == null;
        }

        Decision withReason(final NoOfferReason value) {
            return new Decision(state, client, buyer, score, value, advanceRate, delayProbable, calculated, finalEconomics, tr, rank,
                    clientRank, limitOrder, limitAvailable, reducedToLimit);
        }

        Decision withDelay(final boolean value) {
            return new Decision(state, client, buyer, score, reason, advanceRate, value, calculated, finalEconomics, tr, rank,
                    clientRank, limitOrder, limitAvailable, reducedToLimit);
        }

        Decision withClientRank(final int value) {
            return new Decision(state, client, buyer, score, reason, advanceRate, delayProbable, calculated, finalEconomics, tr, rank,
                    value, limitOrder, limitAvailable, reducedToLimit);
        }

        Decision withLimit(final int order, final BigDecimal available) {
            return new Decision(state, client, buyer, score, reason, advanceRate, delayProbable, calculated, finalEconomics, tr, rank,
                    clientRank, order, available, reducedToLimit);
        }

        Decision withFinal(final Economics.Result value, final boolean reduced) {
            return new Decision(state, client, buyer, score, reason, advanceRate, delayProbable, calculated, value, tr, rank,
                    clientRank, limitOrder, limitAvailable, reduced);
        }
    }
}
