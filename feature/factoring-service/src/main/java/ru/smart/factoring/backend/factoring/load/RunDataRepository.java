package ru.smart.factoring.backend.factoring.load;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.smart.factoring.backend.factoring.model.Upd;
import ru.smart.factoring.backend.factoring.reconciliation.Reconciliation;

import java.time.LocalDate;
import java.util.List;

/**
 * УПД и платежи клиентов прогона не позже даты расчёта (ПР-9, Р-31) - вход блоков 2-5.
 */
@Repository
@RequiredArgsConstructor
public class RunDataRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public List<Upd> upds(final RunScope scope, final LocalDate calcDate) {
        return jdbc.query("""
                        select u.id, u.client_id, u.buyer_id, u.buyer_inn, u.contract_id, k.number as contract_number,
                               k.contract_date, u.number, u.upd_date, u.amount, coalesce(k.deferral_days, 0) as deferral_days
                        from upd u
                        left join contract k on k.id = u.contract_id
                        where u.client_id in (:clientIds) and u.upd_date <= :calcDate
                        order by u.id""",
                params(scope, calcDate),
                (rs, rowNum) -> new Upd(rs.getLong("id"), rs.getLong("client_id"), rs.getObject("buyer_id", Long.class),
                        rs.getString("buyer_inn"), rs.getObject("contract_id", Long.class), rs.getString("contract_number"),
                        rs.getObject("contract_date", LocalDate.class), rs.getString("number"),
                        rs.getObject("upd_date", LocalDate.class), rs.getBigDecimal("amount"), rs.getInt("deferral_days")));
    }

    public List<Reconciliation.Payment> payments(final RunScope scope, final LocalDate calcDate) {
        return jdbc.query("""
                        select id, source_id, client_id, payer_inn, amount, payment_date, purpose
                        from payment
                        where client_id in (:clientIds) and payment_date <= :calcDate
                        order by id""",
                params(scope, calcDate),
                (rs, rowNum) -> new Reconciliation.Payment(rs.getLong("id"), rs.getString("source_id"), rs.getLong("client_id"),
                        rs.getString("payer_inn"), rs.getBigDecimal("amount"), rs.getObject("payment_date", LocalDate.class),
                        rs.getString("purpose")));
    }

    private static MapSqlParameterSource params(final RunScope scope, final LocalDate calcDate) {
        return new MapSqlParameterSource()
                .addValue("clientIds", scope.clientIds().isEmpty() ? List.of(-1L) : scope.clientIds())
                .addValue("calcDate", calcDate);
    }
}
