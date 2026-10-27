package ru.smart.factoring.backend.factoring.scoring;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.smart.factoring.backend.factoring.json.Json;
import ru.smart.factoring.backend.factoring.ml.ModelClient;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Данные покупателей для блока 4 и сохранение его результатов.
 */
@Repository
@RequiredArgsConstructor
public class ScoringRepository {
    private static final TypeReference<List<ModelClient.Reason>> REASONS = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

    /**
     * Профиль, внешние данные (ЕГРЮЛ, ЕФРСБ, дата данных) и отчётность покупателей.
     */
    public Map<Long, BuyerData> buyers(final Collection<Long> buyerIds) {
        final Map<Long, BuyerData> buyers = new LinkedHashMap<>();
        if (buyerIds.isEmpty()) {
            return buyers;
        }
        final MapSqlParameterSource params = new MapSqlParameterSource("ids", buyerIds);
        final Map<Long, List<ModelClient.Financials>> financials = new HashMap<>();
        jdbc.query("select * from buyer_financials where buyer_id in (:ids) order by buyer_id, year", params, rs -> {
            financials.computeIfAbsent(rs.getLong("buyer_id"), id -> new ArrayList<>()).add(new ModelClient.Financials(
                    rs.getInt("year"), rs.getBoolean("simplified"), rs.getBigDecimal("assets_total"), rs.getBigDecimal("current_assets"),
                    rs.getBigDecimal("receivables"), rs.getBigDecimal("cash"), rs.getBigDecimal("equity"),
                    rs.getBigDecimal("long_term_borrowings"), rs.getBigDecimal("short_term_liabilities"),
                    rs.getBigDecimal("short_term_borrowings"), rs.getBigDecimal("payables"), rs.getBigDecimal("revenue"),
                    rs.getBigDecimal("cost_of_sales"), rs.getBigDecimal("sales_profit"), rs.getBigDecimal("interest_payable"),
                    rs.getBigDecimal("net_profit")));
        });
        jdbc.query("""
                select b.id, b.inn, b.okved, b.okved_section, b.region, b.registration_date,
                       e.egrul_status, e.bankruptcy_stage, e.bankruptcy_end_date, e.data_date
                from buyer b
                left join buyer_external_data e on e.buyer_id = b.id
                where b.id in (:ids)
                order by b.id""", params, rs -> {
            final long id = rs.getLong("id");
            buyers.put(id, new BuyerData(id, new ModelClient.Buyer(rs.getString("inn"), rs.getString("okved"),
                    rs.getString("okved_section"), rs.getString("region"), rs.getObject("registration_date", LocalDate.class),
                    financials.getOrDefault(id, List.of())),
                    rs.getString("egrul_status"), rs.getString("bankruptcy_stage"),
                    rs.getObject("bankruptcy_end_date", LocalDate.class), rs.getObject("data_date", LocalDate.class)));
        });
        return buyers;
    }

    /**
     * УПД покупателей у клиентов вне прогона - по последнему успешному прогону, в котором они считались:
     * история оплат покупателя - по всем клиентам банка (п. 4.3, 4.5).
     */
    public List<ModelClient.HistoryUpd> historyOutsideScope(final Collection<Long> buyerIds, final Collection<Long> clientIds,
                                                            final LocalDate calcDate) {
        if (buyerIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                        select distinct on (us.upd_id) u.id, u.number, c.inn as client_inn, u.buyer_inn, u.amount, u.upd_date,
                               us.planned_due_date,
                               us.paid_date, us.outstanding, u.amount - coalesce(us.paid_amount, 0) as open_amount
                        from upd_state us
                        join run r on r.id = us.run_id and r.status in ('DONE', 'DONE_WITH_WARNINGS')
                        join upd u on u.id = us.upd_id
                        join client c on c.id = u.client_id
                        where u.buyer_id in (:buyerIds) and u.client_id not in (:clientIds) and u.upd_date <= :calcDate
                        order by us.upd_id, us.run_id desc""",
                new MapSqlParameterSource()
                        .addValue("buyerIds", buyerIds)
                        .addValue("clientIds", clientIds.isEmpty() ? List.of(-1L) : clientIds)
                        .addValue("calcDate", calcDate),
                (rs, rowNum) -> {
                    final LocalDate paidDate = rs.getObject("paid_date", LocalDate.class);
                    final boolean paid = paidDate != null && !paidDate.isAfter(calcDate);
                    return new ModelClient.HistoryUpd(String.valueOf(rs.getLong("id")), rs.getString("number"), rs.getString("client_inn"),
                            rs.getString("buyer_inn"), rs.getBigDecimal("amount"),
                            rs.getObject("upd_date", LocalDate.class), rs.getObject("planned_due_date", LocalDate.class),
                            paid ? "PAID" : "OPEN", paid ? paidDate : null, paid ? BigDecimal.ZERO : rs.getBigDecimal("open_amount"));
                });
    }

    public Optional<CachedPd> cachedPd(final long buyerId, final LocalDate dataDate, final String modelVersion) {
        if (dataDate == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                        select pd, pd_source, low_data, reasons from buyer_pd_cache
                        where buyer_id = :buyerId and data_date = :dataDate and model_version = :modelVersion""",
                new MapSqlParameterSource()
                        .addValue("buyerId", buyerId)
                        .addValue("dataDate", dataDate)
                        .addValue("modelVersion", modelVersion),
                (rs, rowNum) -> new CachedPd(rs.getBigDecimal("pd"), rs.getString("pd_source"), rs.getBoolean("low_data"),
                        json.read(rs.getString("reasons"), REASONS))).stream().findFirst();
    }

    public void cachePd(final long buyerId, final LocalDate dataDate, final String modelVersion, final ModelClient.PdResult result) {
        if (dataDate == null) {
            return;
        }
        jdbc.update("""
                        insert into buyer_pd_cache (buyer_id, data_date, model_version, pd, pd_source, low_data, reasons)
                        values (:buyerId, :dataDate, :modelVersion, :pd, :pdSource, :lowData, cast(:reasons as jsonb))
                        on conflict (buyer_id, data_date, model_version) do nothing""",
                new MapSqlParameterSource()
                        .addValue("buyerId", buyerId)
                        .addValue("dataDate", dataDate)
                        .addValue("modelVersion", modelVersion)
                        .addValue("pd", result.pd())
                        .addValue("pdSource", result.pdSource())
                        .addValue("lowData", Boolean.TRUE.equals(result.lowData()))
                        .addValue("reasons", json.write(result.reasons())));
    }

    public void saveBuyerScores(final long runId, final Collection<ScoringResult.BuyerScore> scores) {
        jdbc.batchUpdate("""
                        insert into buyer_score (run_id, buyer_id, has_external_data, stop_factor, has_payment_history, pd, pd_source,
                                                 low_data, risk_level, reasons, model_version, data_date, from_cache)
                        values (:runId, :buyerId, true, :stopFactor, :hasHistory, :pd, :pdSource, :lowData, :riskLevel,
                                cast(:reasons as jsonb), :modelVersion, :dataDate, :fromCache)""",
                scores.stream()
                        .map(score -> new MapSqlParameterSource()
                                .addValue("runId", runId)
                                .addValue("buyerId", score.buyerId())
                                .addValue("stopFactor", score.stopFactor() != null ? score.stopFactor().name() : null)
                                .addValue("hasHistory", score.hasPaymentHistory())
                                .addValue("pd", score.pd())
                                .addValue("pdSource", score.pdSource())
                                .addValue("lowData", score.lowData())
                                .addValue("riskLevel", score.riskLevel() != null ? score.riskLevel().name() : null)
                                .addValue("reasons", json.write(score.reasons()))
                                .addValue("modelVersion", score.modelVersion())
                                .addValue("dataDate", score.dataDate())
                                .addValue("fromCache", score.fromCache()))
                        .toArray(MapSqlParameterSource[]::new));
    }

    public void saveUpdScores(final long runId, final int delayThresholdDays, final Collection<ScoringResult.UpdScore> scores) {
        jdbc.batchUpdate("""
                        insert into upd_score (run_id, upd_id, status, delay_threshold_days, p_delay, p_overdue_90, expected_delay_days,
                                               no_pair_history, model_version, attempts, error_message)
                        values (:runId, :updId, :status, :threshold, :pDelay, :pOverdue90, :expectedDelayDays, :noPairHistory,
                                :modelVersion, :attempts, :error)""",
                scores.stream()
                        .map(score -> new MapSqlParameterSource()
                                .addValue("runId", runId)
                                .addValue("updId", score.updId())
                                .addValue("status", score.status())
                                .addValue("threshold", delayThresholdDays)
                                .addValue("pDelay", score.pDelay())
                                .addValue("pOverdue90", score.pOverdue90())
                                .addValue("expectedDelayDays", score.expectedDelayDays())
                                .addValue("noPairHistory", score.noPairHistory())
                                .addValue("modelVersion", score.modelVersion())
                                .addValue("attempts", score.attempts())
                                .addValue("error", score.error()))
                        .toArray(MapSqlParameterSource[]::new));
    }

    /**
     * Покупатель из реестра с внешними данными.
     */
    public record BuyerData(long id, ModelClient.Buyer buyer, String egrulStatus, String bankruptcyStage,
                            LocalDate bankruptcyEndDate, LocalDate dataDate) {
    }

    public record CachedPd(BigDecimal pd, String pdSource, boolean lowData, List<ModelClient.Reason> reasons) {
    }
}
