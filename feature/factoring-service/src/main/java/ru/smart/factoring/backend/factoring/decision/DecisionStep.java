package ru.smart.factoring.backend.factoring.decision;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.factoring.categorization.CategorizationStep;
import ru.smart.factoring.backend.factoring.load.RunDataRepository;
import ru.smart.factoring.backend.factoring.load.RunScope;
import ru.smart.factoring.backend.factoring.model.ClientInfo;
import ru.smart.factoring.backend.factoring.model.UpdNumber;
import ru.smart.factoring.backend.factoring.model.UpdState;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunLog;
import ru.smart.factoring.backend.factoring.run.RunStep;
import ru.smart.factoring.backend.factoring.run.StepProgress;
import ru.smart.factoring.backend.factoring.scoring.ScoringResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Блок 5. Решение и предложение: правила, экономика, ранг, три лучших на клиента, деление лимита покупателя,
 * карточки с текстами LLM, очередь менеджера, уведомления (п. 5.1-5.4).
 */
@Component
@RequiredArgsConstructor
public class DecisionStep implements RunStep {
    static final int STEP_NO = 5;

    private final RunDataRepository runData;
    private final DecisionRepository repository;
    private final OfferPublisher publisher;
    private final Explainer explainer;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolTaskExecutor llmTaskExecutor;
    private final RunLog runLog;

    @Override
    public int stepNo() {
        return STEP_NO;
    }

    @Override
    public String name() {
        return "Решение и предложение";
    }

    @Override
    public Result execute(final RunContext context) {
        final RunScope scope = context.get(RunScope.class);
        final List<UpdState> states = context.get(CategorizationStep.UpdStates.class).states();
        final ScoringResult scoring = context.get(ScoringResult.class);
        final Map<Long, ClientInfo> clients = runData.clients(scope);
        final List<UpdState> open = states.stream().filter(UpdState::open).toList();
        final Set<Long> decided = repository.decidedUpdIds(open.stream().map(state -> state.upd().id()).toList());

        final List<DecisionEngine.Candidate> candidates = open.stream()
                .map(state -> new DecisionEngine.Candidate(state, clients.get(state.upd().clientId()),
                        state.upd().buyerId() != null ? scoring.buyers().get(state.upd().buyerId()) : null,
                        scoring.upds().get(state.upd().id()), decided.contains(state.upd().id())))
                .toList();
        final List<DecisionEngine.Decision> decisions = DecisionEngine.decide(candidates, limitRemaining(context, scoring),
                context.getParameters());

        final long started = System.nanoTime();
        final List<OfferPublisher.Offer> offers = explain(context, decisions.stream().filter(DecisionEngine.Decision::offer).toList());
        context.getModelVersions().put("llm", explainer.provider());
        final Map<Explainer.Source, Long> textSources = logTexts(context, offers, Duration.ofNanos(System.nanoTime() - started));

        final OfferPublisher.Published published = Objects.requireNonNull(transactionTemplate.execute(status -> {
            final OfferPublisher.Published result = publisher.publish(context, offers, states, scope.clientIds());
            repository.saveDecisions(context.getRunId(), decisions, result.newUpdIds());
            return result;
        }));
        final Map<String, Object> metrics = metrics(context, decisions, offers, published);
        final Map<String, Long> bySource = new TreeMap<>();
        textSources.forEach((source, count) -> bySource.put(source.name(), count));
        metrics.put("textsBySource", bySource);
        // сбой LLM не блокирует предложение (п. 5.3), но шаг - «с предупреждением», чтобы оператор его увидел
        return textSources.getOrDefault(Explainer.Source.FAILED, 0L) > 0 ? Result.warning(metrics) : Result.done(metrics);
    }

    /**
     * В журнал прогона: итог по текстам карточек и каждая карточка, для которой LLM не ответила (с причиной).
     */
    private Map<Explainer.Source, Long> logTexts(final RunContext context, final List<OfferPublisher.Offer> offers, final Duration took) {
        final Map<Explainer.Source, Long> bySource = offers.stream()
                .collect(Collectors.groupingBy(offer -> offer.texts().source(), () -> new EnumMap<>(Explainer.Source.class),
                        Collectors.counting()));
        if (offers.isEmpty()) {
            return bySource;
        }
        final List<RunLog.Entry> log = new ArrayList<>();
        final long failed = bySource.getOrDefault(Explainer.Source.FAILED, 0L);
        log.add(RunLog.Entry.of(context.getRunId(), failed > 0 ? RunLog.Level.WARN : RunLog.Level.INFO, STEP_NO, "LLM_TEXTS",
                "Тексты карточек (%s): %d, от LLM %d, из кеша %d, шаблоном %d, без черновика из-за сбоя LLM %d; %d мс".formatted(
                        explainer.provider(), offers.size(), bySource.getOrDefault(Explainer.Source.LLM, 0L),
                        bySource.getOrDefault(Explainer.Source.CACHE, 0L), bySource.getOrDefault(Explainer.Source.TEMPLATE, 0L), failed,
                        took.toMillis())));
        offers.stream().filter(offer -> offer.texts().source() == Explainer.Source.FAILED).forEach(offer -> log.add(RunLog.Entry.of(
                        context.getRunId(), RunLog.Level.WARN, STEP_NO, "LLM_ERROR",
                        "Карточка " + UpdNumber.label(offer.decision().state().upd().number()) + " без черновика: " + offer.texts().error())
                .client(offer.decision().state().upd().clientId())
                .upd(offer.decision().state().upd().id())));
        runLog.write(log);
        return bySource;
    }

    /**
     * Тексты LLM - параллельно (llmTaskExecutor): последовательно сотни вызовов GigaChat не уложились бы в НФР-1.1.
     * Это самая долгая часть прогона (сотни запросов к LLM) - экран видит, сколько карточек готово (Р-57).
     */
    private List<OfferPublisher.Offer> explain(final RunContext context, final List<DecisionEngine.Decision> decisions) {
        final StepProgress progress = context.progress(STEP_NO, "Тексты карточек (" + explainer.provider() + ")", decisions.size());
        final List<CompletableFuture<OfferPublisher.Offer>> futures = decisions.stream()
                .map(decision -> CompletableFuture.supplyAsync(() -> {
                    final OfferPublisher.Offer offer = new OfferPublisher.Offer(decision, explainer.explain(decision));
                    progress.advance();
                    return offer;
                }, llmTaskExecutor))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * Остаток лимита покупателя до деления: лимит по фактическому уровню риска минус финансирование
     * по переданным в факторинг и не оплаченным УПД всех клиентов банка (п. 5.3).
     */
    private Map<Long, BigDecimal> limitRemaining(final RunContext context, final ScoringResult scoring) {
        final Map<Long, BigDecimal> funded = repository.fundedByBuyer(context.getRunId(), scoring.buyers().keySet());
        final Map<Long, BigDecimal> remaining = new HashMap<>();
        scoring.buyers().values().stream()
                .filter(buyer -> buyer.riskLevel() != null && buyer.riskLevel() != RiskLevel.D)
                .forEach(buyer -> remaining.put(buyer.buyerId(), context.getParameters().buyerLimit().get(buyer.riskLevel())
                        .subtract(funded.getOrDefault(buyer.buyerId(), BigDecimal.ZERO)).max(BigDecimal.ZERO)));
        return remaining;
    }

    private Map<String, Object> metrics(final RunContext context, final List<DecisionEngine.Decision> decisions,
                                        final List<OfferPublisher.Offer> offers, final OfferPublisher.Published published) {
        final Map<String, Long> byReason = decisions.stream()
                .filter(decision -> !decision.offer())
                .collect(Collectors.groupingBy(decision -> decision.reason().name(), TreeMap::new, Collectors.counting()));
        final BigDecimal ead = offers.stream().map(offer -> offer.decision().finalEconomics().ead()).reduce(BigDecimal.ZERO, BigDecimal::add);
        final BigDecimal income = offers.stream().map(offer -> offer.decision().finalEconomics().income())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        final BigDecimal expectedAccepted = offers.stream().map(offer -> offer.decision().tr()).reduce(BigDecimal.ZERO, BigDecimal::add);
        context.getSummary().put("offerCount", published.queued());
        context.getSummary().put("eadTotal", ead);

        final Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("decisions", decisions.size());
        metrics.put("offers", offers.size());
        metrics.put("noOffers", decisions.size() - offers.size());
        metrics.put("noOfferByReason", byReason);
        metrics.put("eadTotal", ead);
        metrics.put("incomeTotal", income);
        metrics.put("expectedAccepted", expectedAccepted);
        metrics.put("reducedToLimit", offers.stream().filter(offer -> offer.decision().reducedToLimit()).count());
        metrics.put("queuedOffers", published.queued());
        metrics.put("newOffers", published.newUpdIds().size());
        metrics.put("revokedOffers", published.revoked());
        metrics.put("reminders", published.reminders());
        metrics.put("newOfferNotifications", published.newOfferNotifications());
        metrics.put("withoutMessageDraft", offers.stream().filter(offer -> offer.texts().messageDraft() == null).count());
        return metrics;
    }
}
