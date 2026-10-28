package ru.smart.factoring.backend.factoring.categorization;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.smart.factoring.backend.factoring.json.Json;
import ru.smart.factoring.backend.factoring.load.RunScope;
import ru.smart.factoring.backend.factoring.model.Categories;
import ru.smart.factoring.backend.factoring.model.UpdNumber;
import ru.smart.factoring.backend.factoring.model.UpdState;
import ru.smart.factoring.backend.factoring.reconciliation.ReconciliationStep;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunLog;
import ru.smart.factoring.backend.factoring.run.RunStep;
import ru.smart.factoring.backend.factoring.run.LogText;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Блок 3. Категоризация дебиторки: категории 1-8 на дату расчёта, история платежей, уведомления о просрочке
 * и сигналы в риск-подразделение (п. 3.1-3.12).
 * <p>
 * Отзыв предложений по просроченным и оплаченным УПД (п. 3.9) применяется в блоке 5 вместе с публикацией очереди (Р-32).
 * Уведомления и сигналы - результат прогона: эмулятор CRM показывает их из последнего успешного прогона (Р-33).
 */
@Component
@RequiredArgsConstructor
public class CategorizationStep implements RunStep {
    private static final int STEP_NO = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final RunLog runLog;
    private final Json json;

    @Override
    public int stepNo() {
        return STEP_NO;
    }

    @Override
    public String name() {
        return "Категоризация дебиторки";
    }

    @Override
    public Result execute(final RunContext context) {
        final ReconciliationStep.ReconciledUpds reconciled = context.get(ReconciliationStep.ReconciledUpds.class);
        final List<UpdState> states = reconciled.upds().stream()
                .map(upd -> Categories.state(upd, reconciled.outcome().payoffs().get(upd.id()), context.getCalcDate()))
                .toList();
        final Map<Long, Long> managers = managers(context.get(RunScope.class));
        final Result result = transactionTemplate.execute(status -> persist(context, states, managers));
        context.put(UpdStates.class, new UpdStates(states));
        return Objects.requireNonNull(result);
    }

    private Result persist(final RunContext context, final List<UpdState> states, final Map<Long, Long> managers) {
        final long runId = context.getRunId();
        jdbc.batchUpdate("""
                        insert into upd_state (run_id, upd_id, planned_due_date, days_to_due, paid_amount, outstanding, payment_status,
                                               paid_date, delay_days, category)
                        values (:runId, :updId, :plannedDueDate, :daysToDue, :paid, :outstanding, :paymentStatus, :paidDate,
                                :delayDays, :category)""",
                states.stream()
                        .map(state -> new MapSqlParameterSource()
                                .addValue("runId", runId)
                                .addValue("updId", state.upd().id())
                                .addValue("plannedDueDate", state.plannedDueDate())
                                .addValue("daysToDue", state.daysToDue())
                                .addValue("paid", state.paid())
                                .addValue("outstanding", state.outstanding())
                                .addValue("paymentStatus", state.paymentStatus().name())
                                .addValue("paidDate", state.paidDate())
                                .addValue("delayDays", state.delayDays())
                                .addValue("category", state.category()))
                        .toArray(MapSqlParameterSource[]::new));

        final List<UpdState> overdue = states.stream().filter(UpdState::overdue).toList();
        final List<MapSqlParameterSource> notifications = new ArrayList<>();
        final List<RunLog.Entry> log = new ArrayList<>();
        for (final UpdState state : overdue) {
            final int overdueDays = -state.daysToDue();
            final Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("updNumber", state.upd().number());
            payload.put("outstanding", state.outstanding());
            payload.put("overdueDays", overdueDays);
            payload.put("category", state.category());
            notifications.add(notification(runId, managers.get(state.upd().clientId()), "OVERDUE", state, payload));
            log.add(entry(runId, "OVERDUE_NOTIFICATION", "Просрочка оплаты по " + UpdNumber.label(state.upd().number()) + ": остаток "
                    + LogText.money(state.outstanding()) + ", " + overdueDays + " дн. (п. 3.10 а)", state));
            log.add(entry(runId, "CASH_GAP_SCENARIO", UpdNumber.label(state.upd().number())
                    + " передан в сценарий «закрытие кассового разрыва» (п. 3.10 б, имитация)", state));
        }

        // п. 3.10 в: сигнал о проблемном покупателе - по просрочке более 30 дней (категории 5 и 7), один на покупателя
        final Map<String, List<UpdState>> problemBuyers = overdue.stream()
                .filter(state -> state.category() == Categories.PARTIAL_OVERDUE_OVER_30
                        || state.category() == Categories.UNPAID_OVERDUE_OVER_30)
                .collect(Collectors.groupingBy(state -> state.upd().buyerInn(), TreeMap::new, Collectors.toList()));
        problemBuyers.forEach((buyerInn, upds) -> {
            final BigDecimal outstanding = upds.stream().map(UpdState::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
            final Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("buyerInn", buyerInn);
            payload.put("updNumbers", upds.stream().map(state -> state.upd().number()).toList());
            payload.put("outstanding", outstanding);
            jdbc.update("""
                            insert into imitation_risk_signal (run_id, buyer_inn, upd_ids, payload)
                            values (:runId, :buyerInn, cast(:updIds as jsonb), cast(:payload as jsonb))""",
                    new MapSqlParameterSource()
                            .addValue("runId", runId)
                            .addValue("buyerInn", buyerInn)
                            .addValue("updIds", json.write(upds.stream().map(state -> state.upd().id()).toList()))
                            .addValue("payload", json.write(payload)));
            upds.stream().map(state -> managers.get(state.upd().clientId())).distinct()
                    .forEach(managerId -> notifications.add(notification(runId, managerId, "RISK_SIGNAL", upds.getFirst(), payload)));
            log.add(entry(runId, "RISK_SIGNAL", "Сигнал в риск-подразделение: покупатель ИНН " + buyerInn + ", просрочка более 30 дней по "
                    + upds.size() + " УПД (п. 3.10 в)", upds.getFirst()));
        });

        jdbc.batchUpdate("""
                        insert into notification (run_id, manager_id, type, client_id, buyer_id, upd_id, payload)
                        values (:runId, :managerId, :type, :clientId, :buyerId, :updId, cast(:payload as jsonb))""",
                notifications.toArray(MapSqlParameterSource[]::new));
        runLog.write(log);

        final Map<String, Long> byCategory = states.stream()
                .filter(UpdState::open)
                .collect(Collectors.groupingBy(state -> String.valueOf(state.category()), TreeMap::new, Collectors.counting()));
        final long open = states.stream().filter(UpdState::open).count();
        context.getSummary().put("openUpdCount", open);

        final Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("openUpds", open);
        metrics.put("openAmount", states.stream().filter(UpdState::open).map(UpdState::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add));
        metrics.put("paidUpds", states.size() - open);
        metrics.put("byCategory", byCategory);
        metrics.put("overdueNotifications", overdue.size());
        metrics.put("riskSignals", problemBuyers.size());
        return Result.done(metrics);
    }

    private Map<Long, Long> managers(final RunScope scope) {
        final Map<Long, Long> managers = new HashMap<>();
        jdbc.query("select id, manager_id from client where id in (:clientIds)",
                new MapSqlParameterSource("clientIds", scope.clientIds().isEmpty() ? List.of(-1L) : scope.clientIds()),
                rs -> {
                    managers.put(rs.getLong("id"), rs.getLong("manager_id"));
                });
        return managers;
    }

    private MapSqlParameterSource notification(final long runId, final Long managerId, final String type, final UpdState state,
                                               final Map<String, Object> payload) {
        return new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("managerId", managerId)
                .addValue("type", type)
                .addValue("clientId", state.upd().clientId())
                .addValue("buyerId", state.upd().buyerId())
                .addValue("updId", state.upd().id())
                .addValue("payload", json.write(payload));
    }

    private static RunLog.Entry entry(final long runId, final String code, final String message, final UpdState state) {
        return RunLog.Entry.of(runId, RunLog.Level.INFO, STEP_NO, code, message)
                .client(state.upd().clientId())
                .buyer(state.upd().buyerId())
                .upd(state.upd().id());
    }

    /**
     * Результат блока 3 для блоков 4-5: все УПД клиентов прогона на дату расчёта, открытые и оплаченные.
     */
    public record UpdStates(List<UpdState> states) {
    }
}
