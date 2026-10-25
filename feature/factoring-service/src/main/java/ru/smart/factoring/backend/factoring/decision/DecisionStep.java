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
import ru.smart.factoring.backend.factoring.model.UpdState;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunStep;
import ru.smart.factoring.backend.factoring.scoring.ScoringResult;

import java.math.BigDecimal;
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

        final List<OfferPublisher.Offer> offers = explain(decisions.stream().filter(DecisionEngine.Decision::offer).toList());
        context.getModelVersions().put("llm", explainer.provider());

        final OfferPublisher.Published published = Objects.requireNonNull(transactionTemplate.execute(status -> {
            final OfferPublisher.Published result = publisher.publish(context, offers, states, scope.clientIds());
            repository.saveDecisions(context.getRunId(), decisions, result.newUpdIds());
            return result;
        }));
        return Result.done(metrics(context, decisions, offers, published));
    }

    /**
     * Тексты LLM - параллельно (llmTaskExecutor): последовательно сотни вызовов GigaChat не уложились бы в НФР-1.1.
     */
    private List<OfferPublisher.Offer> explain(final List<DecisionEngine.Decision> decisions) {
        final List<CompletableFuture<OfferPublisher.Offer>> futures = decisions.stream()
                .map(decision -> CompletableFuture.supplyAsync(() -> new OfferPublisher.Offer(decision, explainer.explain(decision)),
                        llmTaskExecutor))
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
