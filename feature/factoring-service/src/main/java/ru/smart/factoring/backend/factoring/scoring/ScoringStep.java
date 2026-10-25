package ru.smart.factoring.backend.factoring.scoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.factoring.categorization.CategorizationStep;
import ru.smart.factoring.backend.factoring.load.RunDataRepository;
import ru.smart.factoring.backend.factoring.load.RunScope;
import ru.smart.factoring.backend.factoring.ml.ModelClient;
import ru.smart.factoring.backend.factoring.ml.ModelProperties;
import ru.smart.factoring.backend.factoring.model.ClientInfo;
import ru.smart.factoring.backend.factoring.model.UpdState;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunLog;
import ru.smart.factoring.backend.factoring.run.RunStep;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Блок 4. Скоринг покупателей: стоп-факторы, PD покупателя один раз на всех клиентов (сетевой эффект), уровень риска,
 * задержка по УПД, пять ситуаций отсутствия данных (п. 4.1-4.5).
 * <p>
 * Сбой модели по части объектов не останавливает прогон: обработка повторяется, затем «ошибка оценки» и статус шага
 * «предупреждение» (НФР-3, НФР-4.1). Нет данных или истории оплат - штатный результат (НФР-4.2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoringStep implements RunStep {
    private static final int STEP_NO = 4;
    private static final String SCORED = "SCORED";
    private static final String NO_PAYMENT_HISTORY = "NO_PAYMENT_HISTORY";
    private static final String ERROR = "ERROR";

    private final ModelClient modelClient;
    private final ModelProperties modelProperties;
    private final ScoringRepository repository;
    private final RunDataRepository runData;
    private final RunLog runLog;

    @Override
    public int stepNo() {
        return STEP_NO;
    }

    @Override
    public String name() {
        return "Скоринг покупателей";
    }

    @Override
    public Result execute(final RunContext context) {
        final RunScope scope = context.get(RunScope.class);
        final List<UpdState> states = context.get(CategorizationStep.UpdStates.class).states();
        final Map<Long, ClientInfo> clients = runData.clients(scope);
        final ModelClient.Versions versions = modelClient.versions();
        context.getModelVersions().put("pd", versions.pd());
        context.getModelVersions().put("delay", versions.delay());
        final List<RunLog.Entry> log = new ArrayList<>();
        final long runId = context.getRunId();

        // покупатели открытых УПД из реестра; УПД без покупателя в реестре - «нет данных по покупателю» (п. 1.4)
        final Set<Long> buyerIds = states.stream()
                .filter(UpdState::open)
                .map(state -> state.upd().buyerId())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        final Map<Long, ScoringRepository.BuyerData> buyers = repository.buyers(buyerIds);
        final List<ModelClient.HistoryUpd> history = history(states, clients, buyerIds, scope, context);
        final Set<String> buyersWithHistory = history.stream()
                .filter(item -> "PAID".equals(item.status()))
                .map(ModelClient.HistoryUpd::buyerInn)
                .collect(Collectors.toSet());

        final Map<Long, ScoringResult.BuyerScore> buyerScores = new LinkedHashMap<>();
        final List<ScoringRepository.BuyerData> toScore = new ArrayList<>();
        for (final ScoringRepository.BuyerData buyer : buyers.values()) {
            final StopFactors.StopFactor stop = StopFactors.classify(buyer.egrulStatus(), buyer.bankruptcyStage(), buyer.bankruptcyEndDate());
            final boolean hasHistory = buyersWithHistory.contains(buyer.buyer().inn());
            if (stop != null) {
                buyerScores.put(buyer.id(), new ScoringResult.BuyerScore(buyer.id(), buyer.buyer().inn(), stop, hasHistory, null, null,
                        false, null, List.of(), null, buyer.dataDate(), false, null));
                log.add(RunLog.Entry.of(runId, RunLog.Level.INFO, STEP_NO, "STOP_FACTOR",
                        "Стоп-фактор " + stop + " у покупателя ИНН " + buyer.buyer().inn() + ": " + buyer.egrulStatus()).buyer(buyer.id()));
            } else {
                toScore.add(buyer);
            }
        }
        scorePd(context, versions.pd(), toScore, buyersWithHistory, buyerScores, log);

        final Map<Long, ScoringResult.UpdScore> updScores = scoreDelay(context, versions.delay(), states, clients, buyers, buyerScores,
                history, log);

        repository.saveBuyerScores(runId, buyerScores.values());
        repository.saveUpdScores(runId, context.getParameters().delayThresholdDays(), updScores.values());
        runLog.write(log);
        context.put(ScoringResult.class, new ScoringResult(buyerScores, updScores));

        final long errors = buyerScores.values().stream().filter(score -> score.error() != null).count()
                + updScores.values().stream().filter(score -> ERROR.equals(score.status())).count();
        final Map<String, Object> metrics = metrics(context, states, buyerScores, updScores, errors);
        return errors > 0 ? Result.warning(metrics) : Result.done(metrics);
    }

    /**
     * История оплат покупателей (п. 3.11): УПД клиентов прогона на дату расчёта и УПД других клиентов из прошлых прогонов.
     */
    private List<ModelClient.HistoryUpd> history(final List<UpdState> states, final Map<Long, ClientInfo> clients,
                                                 final Set<Long> buyerIds, final RunScope scope, final RunContext context) {
        final List<ModelClient.HistoryUpd> history = new ArrayList<>();
        for (final UpdState state : states) {
            if (state.upd().buyerId() == null || !buyerIds.contains(state.upd().buyerId())) {
                continue;
            }
            history.add(new ModelClient.HistoryUpd(clients.get(state.upd().clientId()).inn(), state.upd().buyerInn(),
                    state.upd().amount(), state.upd().updDate(), state.plannedDueDate(), state.open() ? "OPEN" : "PAID",
                    state.paidDate(), state.outstanding()));
        }
        history.addAll(repository.historyOutsideScope(buyerIds, scope.clientIds(), context.getCalcDate()));
        return history;
    }

    private void scorePd(final RunContext context, final String modelVersion, final List<ScoringRepository.BuyerData> buyers,
                         final Set<String> buyersWithHistory, final Map<Long, ScoringResult.BuyerScore> scores,
                         final List<RunLog.Entry> log) {
        final long runId = context.getRunId();
        final List<ScoringRepository.BuyerData> missing = new ArrayList<>();
        for (final ScoringRepository.BuyerData buyer : buyers) {
            // PD пересчитывается, только если обновились данные покупателя или версия модели (п. 4.2)
            repository.cachedPd(buyer.id(), buyer.dataDate(), modelVersion).ifPresentOrElse(
                    cached -> scores.put(buyer.id(), buyerScore(context, buyer, buyersWithHistory, cached.pd(), cached.pdSource(),
                            cached.lowData(), cached.reasons(), modelVersion, true, null)),
                    () -> missing.add(buyer));
        }
        for (final List<ScoringRepository.BuyerData> batch : batches(missing)) {
            final Attempt<ModelClient.PdResponse> response = withRetries(context,
                    () -> modelClient.scorePd(new ModelClient.PdRequest(context.getCalcDate(),
                            batch.stream().map(ScoringRepository.BuyerData::buyer).toList())));
            final Map<String, ModelClient.PdResult> results = response.value() == null ? Map.of()
                    : response.value().results().stream().collect(Collectors.toMap(ModelClient.PdResult::inn, result -> result,
                    (a, b) -> a));
            for (final ScoringRepository.BuyerData buyer : batch) {
                final ModelClient.PdResult result = results.get(buyer.buyer().inn());
                if (result == null || !SCORED.equals(result.status()) || result.pd() == null) {
                    final String error = response.error() != null ? response.error()
                            : result != null && result.error() != null ? result.error() : "нет ответа модели PD";
                    scores.put(buyer.id(), buyerScore(context, buyer, buyersWithHistory, null, null, false, List.of(), modelVersion,
                            false, error));
                    log.add(RunLog.Entry.of(runId, RunLog.Level.ERROR, STEP_NO, "SCORING_ERROR",
                            "Ошибка оценки PD покупателя ИНН " + buyer.buyer().inn() + ": " + error).buyer(buyer.id()));
                    continue;
                }
                repository.cachePd(buyer.id(), buyer.dataDate(), modelVersion, result);
                scores.put(buyer.id(), buyerScore(context, buyer, buyersWithHistory, result.pd(), result.pdSource(),
                        Boolean.TRUE.equals(result.lowData()), result.reasons(), modelVersion, false, null));
            }
        }
        scores.values().stream()
                .filter(score -> score.stopFactor() == null && score.error() == null)
                .forEach(score -> {
                    if (score.lowData()) {
                        log.add(RunLog.Entry.of(runId, RunLog.Level.INFO, STEP_NO, "LOW_DATA",
                                "Покупатель ИНН " + score.inn() + ": базовый PD по отрасли и размеру, «мало данных» (п. 4.2)")
                                .buyer(score.buyerId()));
                    }
                    if (!score.hasPaymentHistory()) {
                        log.add(RunLog.Entry.of(runId, RunLog.Level.INFO, STEP_NO, "NO_PAYMENT_HISTORY",
                                "Покупатель ИНН " + score.inn() + ": нет ни одного оплаченного УПД - модель задержки не применяется (п. 4.5)")
                                .buyer(score.buyerId()));
                    }
                });
    }

    private ScoringResult.BuyerScore buyerScore(final RunContext context, final ScoringRepository.BuyerData buyer,
                                                final Set<String> buyersWithHistory, final BigDecimal pd, final String pdSource,
                                                final boolean lowData, final List<ModelClient.Reason> reasons,
                                                final String modelVersion, final boolean fromCache, final String error) {
        final RiskLevel level = pd != null ? context.getParameters().riskLevel(pd) : null;
        return new ScoringResult.BuyerScore(buyer.id(), buyer.buyer().inn(), null, buyersWithHistory.contains(buyer.buyer().inn()),
                pd, pdSource, lowData, level, reasons != null ? reasons : List.of(), modelVersion, buyer.dataDate(), fromCache, error);
    }

    /**
     * Модель задержки - по открытым УПД категорий 1-4 у покупателей с PD (шаг Б, п. 4.3).
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private Map<Long, ScoringResult.UpdScore> scoreDelay(final RunContext context, final String modelVersion,
                                                         final List<UpdState> states, final Map<Long, ClientInfo> clients,
                                                         final Map<Long, ScoringRepository.BuyerData> buyers,
                                                         final Map<Long, ScoringResult.BuyerScore> buyerScores,
                                                         final List<ModelClient.HistoryUpd> history, final List<RunLog.Entry> log) {
        final long runId = context.getRunId();
        final List<UpdState> toScore = states.stream()
                .filter(UpdState::financeable)
                .filter(state -> state.upd().buyerId() != null)
                .filter(state -> {
                    final ScoringResult.BuyerScore score = buyerScores.get(state.upd().buyerId());
                    return score != null && score.pd() != null;
                })
                .toList();
        final Map<Long, ScoringResult.UpdScore> scores = new LinkedHashMap<>();
        for (final List<UpdState> batch : batches(toScore)) {
            final Set<String> batchBuyers = batch.stream().map(state -> state.upd().buyerInn()).collect(Collectors.toSet());
            final ModelClient.DelayRequest request = new ModelClient.DelayRequest(context.getCalcDate(),
                    context.getParameters().delayThresholdDays(),
                    batch.stream().map(state -> state.upd().buyerId()).distinct().map(buyers::get).map(ScoringRepository.BuyerData::buyer)
                            .toList(),
                    batch.stream().map(state -> {
                        final ClientInfo client = clients.get(state.upd().clientId());
                        return new ModelClient.DelayUpd(String.valueOf(state.upd().id()), state.upd().number(), client.inn(),
                                client.industry(), state.upd().buyerInn(), buyerScores.get(state.upd().buyerId()).pd(),
                                state.upd().amount(), state.outstanding(), state.upd().updDate(), state.plannedDueDate(),
                                state.upd().deferralDays());
                    }).toList(),
                    history.stream().filter(item -> batchBuyers.contains(item.buyerInn())).toList());
            final Attempt<ModelClient.DelayResponse> response = withRetries(context, () -> modelClient.scoreDelay(request));
            final Map<String, ModelClient.DelayResult> results = response.value() == null ? Map.of()
                    : response.value().results().stream().collect(Collectors.toMap(ModelClient.DelayResult::updId, result -> result,
                    (a, b) -> a));
            for (final UpdState state : batch) {
                final long updId = state.upd().id();
                final ModelClient.DelayResult result = results.get(String.valueOf(updId));
                if (result == null || ERROR.equals(result.status())
                        || SCORED.equals(result.status()) && (result.pDelay() == null || result.pOverdue90() == null)) {
                    final String error = response.error() != null ? response.error()
                            : result != null && result.error() != null ? result.error() : "нет ответа модели задержки";
                    scores.put(updId, new ScoringResult.UpdScore(updId, ERROR, null, null, null, false, modelVersion,
                            response.attempts(), error));
                    log.add(entry(runId, RunLog.Level.ERROR, "SCORING_ERROR", "Ошибка оценки УПД " + state.upd().number() + ": " + error,
                            state));
                    continue;
                }
                final boolean noPairHistory = Boolean.TRUE.equals(result.noPairHistory());
                scores.put(updId, new ScoringResult.UpdScore(updId, result.status(), result.pDelay(), result.pOverdue90(),
                        result.expectedDelayDays(), noPairHistory && SCORED.equals(result.status()), modelVersion,
                        response.attempts(), null));
                if (SCORED.equals(result.status()) && noPairHistory) {
                    log.add(entry(runId, RunLog.Level.INFO, "NO_PAIR_HISTORY", "УПД " + state.upd().number()
                            + ": нет истории пары, оценка по платёжной дисциплине покупателя (п. 4.5)", state));
                }
            }
        }
        return scores;
    }

    private Map<String, Object> metrics(final RunContext context, final List<UpdState> states,
                                        final Map<Long, ScoringResult.BuyerScore> buyers, final Map<Long, ScoringResult.UpdScore> upds,
                                        final long errors) {
        final Map<String, Long> byLevel = new LinkedHashMap<>();
        final Map<RiskLevel, Long> counted = buyers.values().stream()
                .filter(score -> score.riskLevel() != null)
                .collect(Collectors.groupingBy(ScoringResult.BuyerScore::riskLevel, () -> new EnumMap<>(RiskLevel.class),
                        Collectors.counting()));
        for (final RiskLevel level : RiskLevel.values()) {
            byLevel.put(level.name(), counted.getOrDefault(level, 0L));
        }
        final BigDecimal threshold = context.getParameters().delayProbabilityThreshold();
        final Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("buyers", buyers.size());
        metrics.put("stopFactorBuyers", buyers.values().stream().filter(score -> score.stopFactor() != null).count());
        metrics.put("noPaymentHistoryBuyers", buyers.values().stream()
                .filter(score -> score.stopFactor() == null && !score.hasPaymentHistory()).count());
        metrics.put("lowDataBuyers", buyers.values().stream().filter(ScoringResult.BuyerScore::lowData).count());
        metrics.put("buyersByRiskLevel", byLevel);
        metrics.put("pdFromCache", buyers.values().stream().filter(ScoringResult.BuyerScore::fromCache).count());
        metrics.put("noBuyerDataUpds", states.stream().filter(UpdState::open).filter(state -> state.upd().buyerId() == null).count());
        metrics.put("scoredUpds", upds.values().stream().filter(ScoringResult.UpdScore::scored).count());
        metrics.put("noPaymentHistoryUpds", upds.values().stream().filter(score -> NO_PAYMENT_HISTORY.equals(score.status())).count());
        metrics.put("noPairHistoryUpds", upds.values().stream().filter(ScoringResult.UpdScore::noPairHistory).count());
        metrics.put("delayProbableUpds", upds.values().stream()
                .filter(score -> score.scored() && score.pDelay().compareTo(threshold) > 0).count());
        metrics.put("scoringErrors", errors);
        return metrics;
    }

    /**
     * Вызов модели с повторами (НФР-3): после неудачи всех попыток - ошибка с сообщением.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private <T> Attempt<T> withRetries(final RunContext context, final Supplier<T> call) {
        final int attempts = 1 + Math.max(0, context.getParameters().updRetryCount());
        String error = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return new Attempt<>(call.get(), attempt, null);
            } catch (RuntimeException e) {
                error = "модель недоступна: " + e.getMessage();
                log.warn("Прогон {}: попытка {} вызова модели не удалась", context.getRunId(), attempt, e);
            }
        }
        return new Attempt<>(null, attempts, error);
    }

    private <T> List<List<T>> batches(final List<T> items) {
        final int size = modelProperties.batch();
        final List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            batches.add(items.subList(i, Math.min(items.size(), i + size)));
        }
        return batches;
    }

    private static RunLog.Entry entry(final long runId, final RunLog.Level level, final String code, final String message,
                                      final UpdState state) {
        return RunLog.Entry.of(runId, level, STEP_NO, code, message)
                .client(state.upd().clientId())
                .buyer(state.upd().buyerId())
                .upd(state.upd().id());
    }

    private record Attempt<T>(T value, int attempts, String error) {
    }
}
