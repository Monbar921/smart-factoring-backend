package ru.smart.factoring.backend.factoring.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.factoring.load.RunDataRepository;
import ru.smart.factoring.backend.factoring.load.RunScope;
import ru.smart.factoring.backend.factoring.model.Upd;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunStep;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Блок 2. Сверка оплат: строится заново в каждом прогоне по платежам не позже даты расчёта (Р-1, ПР-9).
 */
@Component
@RequiredArgsConstructor
public class ReconciliationStep implements RunStep {
    private static final int STEP_NO = 2;

    private final RunDataRepository runData;
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public int stepNo() {
        return STEP_NO;
    }

    @Override
    public String name() {
        return "Сверка оплат";
    }

    @Override
    public Result execute(final RunContext context) {
        final RunScope scope = context.get(RunScope.class);
        final List<Upd> upds = runData.upds(scope, context.getCalcDate());
        final List<Reconciliation.Payment> payments = runData.payments(scope, context.getCalcDate());
        final Reconciliation.Outcome outcome = Reconciliation.reconcile(upds, payments);

        jdbc.batchUpdate("""
                        insert into payment_allocation (run_id, payment_id, upd_id, amount, rule)
                        values (:runId, :paymentId, :updId, :amount, :rule)""",
                outcome.allocations().stream()
                        .map(allocation -> new MapSqlParameterSource()
                                .addValue("runId", context.getRunId())
                                .addValue("paymentId", allocation.paymentId())
                                .addValue("updId", allocation.updId())
                                .addValue("amount", allocation.amount())
                                .addValue("rule", allocation.rule().name()))
                        .toArray(MapSqlParameterSource[]::new));
        context.put(ReconciledUpds.class, new ReconciledUpds(upds, outcome));

        final List<Reconciliation.Allocation> unallocated = outcome.allocations().stream()
                .filter(allocation -> allocation.updId() == null)
                .toList();
        final Set<Long> partlyUnallocated = unallocated.stream().map(Reconciliation.Allocation::paymentId).collect(Collectors.toSet());
        final Map<String, Long> byRule = outcome.allocations().stream()
                .collect(Collectors.groupingBy(allocation -> allocation.rule().name(), LinkedHashMap::new, Collectors.counting()));

        final Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("payments", payments.size());
        metrics.put("paymentsAllocated", payments.size() - partlyUnallocated.size());
        metrics.put("paymentsUnallocated", partlyUnallocated.size());
        metrics.put("amountUnallocated", unallocated.stream().map(Reconciliation.Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        metrics.put("updsClosed", outcome.payoffs().values().stream().filter(payoff -> payoff.outstanding().signum() == 0).count());
        metrics.put("allocationsByRule", byRule);
        return Result.done(metrics);
    }

    /**
     * Результат блока 2 для блока 3.
     */
    public record ReconciledUpds(List<Upd> upds, Reconciliation.Outcome outcome) {
    }
}
