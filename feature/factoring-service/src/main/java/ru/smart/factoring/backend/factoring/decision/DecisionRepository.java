package ru.smart.factoring.backend.factoring.decision;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Данные и запись блока 5: решения менеджера, профинансированные суммы, решения по УПД.
 */
@Repository
@RequiredArgsConstructor
public class DecisionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * УПД, по которым менеджер уже принял решение: новое предложение по ним не формируется (п. 6.4).
     */
    public Set<Long> decidedUpdIds(final Collection<Long> updIds) {
        if (updIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "select upd_id from offer where status in ('TRANSFERRED', 'REJECTED') and upd_id in (:ids)",
                new MapSqlParameterSource("ids", updIds), Long.class));
    }

    /**
     * Финансирование по неоплаченным УПД покупателя с отметкой «передано в факторинг» (п. 5.3, 6.5). Оплачен ли УПД -
     * по этому прогону, иначе по последнему успешному; нет сведений - считается неоплаченным.
     */
    public Map<Long, BigDecimal> fundedByBuyer(final long runId, final Collection<Long> buyerIds) {
        final Map<Long, BigDecimal> funded = new HashMap<>();
        if (buyerIds.isEmpty()) {
            return funded;
        }
        jdbc.query("""
                        select o.buyer_id, sum(o.ead) as funded
                        from offer o
                        left join lateral (
                            select us.payment_status
                            from upd_state us
                            where us.upd_id = o.upd_id
                              and (us.run_id = :runId
                                   or us.run_id in (select id from run where status in ('DONE', 'DONE_WITH_WARNINGS')))
                            order by (us.run_id = :runId) desc, us.run_id desc
                            limit 1) st on true
                        where o.status = 'TRANSFERRED' and o.buyer_id in (:buyerIds) and coalesce(st.payment_status, 'UNPAID') <> 'PAID'
                        group by o.buyer_id""",
                new MapSqlParameterSource().addValue("runId", runId).addValue("buyerIds", buyerIds),
                rs -> {
                    funded.put(rs.getLong("buyer_id"), rs.getBigDecimal("funded"));
                });
        return funded;
    }

    /**
     * Предложения в очереди по клиентам прогона, с датой расчёта прогона, который их создал (для напоминаний).
     */
    public List<QueuedOffer> queuedOffers(final Collection<Long> clientIds) {
        return jdbc.query("""
                        select o.id, o.upd_id, r.calc_date as created_calc_date, o.ead, o.price, o.term_days,
                               (select max(nr.calc_date) from notification n join run nr on nr.id = n.run_id
                                where n.offer_id = o.id and n.type = 'REMINDER') as reminded_calc_date
                        from offer o join run r on r.id = o.created_run_id
                        where o.status = 'QUEUED' and o.client_id in (:clientIds)""",
                new MapSqlParameterSource("clientIds", clientIds.isEmpty() ? List.of(-1L) : clientIds),
                (rs, rowNum) -> new QueuedOffer(rs.getLong("id"), rs.getLong("upd_id"), rs.getObject("created_calc_date", LocalDate.class),
                        rs.getBigDecimal("ead"), rs.getBigDecimal("price"), rs.getInt("term_days"),
                        rs.getObject("reminded_calc_date", LocalDate.class)));
    }

    public void saveDecisions(final long runId, final Collection<DecisionEngine.Decision> decisions, final Set<Long> newUpdIds) {
        jdbc.batchUpdate("""
                        insert into upd_decision (run_id, upd_id, category, decision, reason, risk_level, advance_rate, delay_probable,
                            term_days, pd_term, lgd, ead_calc, el_calc, funding_calc, opex_calc, margin_calc, price_cap_calc, price_calc,
                            cap_applied, income_calc, ead_final, el_final, price_final, price_rate_final, income_final, tr, rank_value,
                            client_rank, limit_order, limit_available_before, reduced_to_limit, low_data, is_new)
                        values (:runId, :updId, :category, :decision, :reason, :riskLevel, :advanceRate, :delayProbable,
                            :termDays, :pdTerm, :lgd, :eadCalc, :elCalc, :fundingCalc, :opexCalc, :marginCalc, :priceCapCalc, :priceCalc,
                            :capApplied, :incomeCalc, :eadFinal, :elFinal, :priceFinal, :priceRateFinal, :incomeFinal, :tr, :rank,
                            :clientRank, :limitOrder, :limitAvailable, :reduced, :lowData, :isNew)""",
                decisions.stream().map(decision -> {
                    final Economics.Result calc = decision.calculated();
                    final Economics.Result fin = decision.finalEconomics();
                    return new MapSqlParameterSource()
                            .addValue("runId", runId)
                            .addValue("updId", decision.state().upd().id())
                            .addValue("category", decision.state().category())
                            .addValue("decision", decision.offer() ? "OFFER" : "NO_OFFER")
                            .addValue("reason", decision.reason() != null ? decision.reason().name() : null)
                            .addValue("riskLevel", decision.buyer() != null && decision.buyer().riskLevel() != null
                                    ? decision.buyer().riskLevel().name() : null)
                            .addValue("advanceRate", decision.advanceRate())
                            .addValue("delayProbable", decision.delayProbable())
                            .addValue("termDays", calc != null ? calc.termDays() : null)
                            .addValue("pdTerm", calc != null ? calc.pdTerm() : null)
                            .addValue("lgd", calc != null ? calc.lgd() : null)
                            .addValue("eadCalc", calc != null ? calc.ead() : null)
                            .addValue("elCalc", calc != null ? calc.el() : null)
                            .addValue("fundingCalc", calc != null ? calc.funding() : null)
                            .addValue("opexCalc", calc != null ? calc.opex() : null)
                            .addValue("marginCalc", calc != null ? calc.margin() : null)
                            .addValue("priceCapCalc", calc != null ? calc.priceCap() : null)
                            .addValue("priceCalc", calc != null ? calc.price() : null)
                            .addValue("capApplied", calc != null ? calc.capApplied() : null)
                            .addValue("incomeCalc", calc != null ? calc.income() : null)
                            .addValue("eadFinal", fin != null ? fin.ead() : null)
                            .addValue("elFinal", fin != null ? fin.el() : null)
                            .addValue("priceFinal", fin != null ? fin.price() : null)
                            .addValue("priceRateFinal", fin != null ? fin.priceRate() : null)
                            .addValue("incomeFinal", fin != null ? fin.income() : null)
                            .addValue("tr", decision.tr())
                            .addValue("rank", decision.rank())
                            .addValue("clientRank", decision.clientRank())
                            .addValue("limitOrder", decision.limitOrder() > 0 ? decision.limitOrder() : null)
                            .addValue("limitAvailable", decision.limitAvailable())
                            .addValue("reduced", decision.reducedToLimit())
                            .addValue("lowData", decision.buyer() != null && decision.buyer().lowData())
                            .addValue("isNew", newUpdIds.contains(decision.state().upd().id()));
                }).toArray(MapSqlParameterSource[]::new));
    }

    /**
     * Предложение в очереди перед публикацией прогона.
     *
     * @param ead, price, termDays условия, которые видел менеджер: изменились - уведомление OFFER_CHANGED (П4-4)
     * @param remindedCalcDate     дата расчёта последнего напоминания; на ту же дату напоминание не повторяется
     */
    public record QueuedOffer(long id, long updId, LocalDate createdCalcDate, BigDecimal ead, BigDecimal price, int termDays,
                              LocalDate remindedCalcDate) {
    }
}
