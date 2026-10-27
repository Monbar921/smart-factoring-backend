package ru.smart.factoring.backend.factoring.details;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ru.smart.factoring.backend.api.common.ClientRefDto;
import ru.smart.factoring.backend.api.common.PageDto;
import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.api.crm.ReasonDto;
import ru.smart.factoring.backend.api.details.FunnelDropDto;
import ru.smart.factoring.backend.api.details.FunnelStageDto;
import ru.smart.factoring.backend.api.details.RunBuyerDto;
import ru.smart.factoring.backend.api.details.RunClientDto;
import ru.smart.factoring.backend.api.details.RunUpdDto;
import ru.smart.factoring.backend.api.details.TraceStepDto;
import ru.smart.factoring.backend.api.details.UpdTraceDto;
import ru.smart.factoring.backend.api.run.RunLogEntryDto;
import ru.smart.factoring.backend.factoring.error.ApiException;
import ru.smart.factoring.backend.factoring.json.Json;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Детализация прогона для экрана процесса: воронка (ЭП-4), списки объектов и путь УПД по шагам (ЭП-5).
 * Объёмы демо (около 1 500 УПД) позволяют фильтровать и листать в памяти.
 */
@Service
@RequiredArgsConstructor
public class RunDetailsService {
    private static final TypeReference<List<ReasonDto>> REASONS = new TypeReference<>() {
    };
    private static final int MAX_PAGE_SIZE = 500;
    private static final String UPD_ROWS = """
            select u.id, u.number, u.upd_date, u.amount, c.id as client_id, c.inn as client_inn, c.name as client_name,
                   u.buyer_id, coalesce(b.inn, u.buyer_inn) as buyer_inn, b.name as buyer_name,
                   us.outstanding, us.payment_status, us.planned_due_date, us.days_to_due, us.category,
                   bs.pd, bs.risk_level, s.p_delay, s.p_overdue_90, d.decision, d.reason, d.limit_order, d.ead_final, d.income_final
            from upd_state us
            join upd u on u.id = us.upd_id
            join client c on c.id = u.client_id
            left join buyer b on b.id = u.buyer_id
            left join buyer_score bs on bs.run_id = us.run_id and bs.buyer_id = u.buyer_id
            left join upd_score s on s.run_id = us.run_id and s.upd_id = us.upd_id
            left join upd_decision d on d.run_id = us.run_id and d.upd_id = us.upd_id
            where us.run_id = :runId
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

    public List<FunnelStageDto> funnel(final long runId) {
        final List<RunUpdDto> open = rows(runId, null).stream().filter(row -> !"PAID".equals(row.paymentStatus())).toList();
        final List<FunnelStageDto> stages = new ArrayList<>();
        List<RunUpdDto> remaining = open;
        for (final FunnelStage stage : FunnelStage.values()) {
            final List<RunUpdDto> dropped = stage == FunnelStage.OPEN ? List.of()
                    : remaining.stream().filter(row -> row.reason() != null && stage.name().equals(row.stage())).toList();
            remaining = remaining.stream().filter(row -> !dropped.contains(row)).toList();
            final Map<String, List<RunUpdDto>> byReason = dropped.stream()
                    .collect(Collectors.groupingBy(RunUpdDto::reason, TreeMap::new, Collectors.toList()));
            stages.add(new FunnelStageDto(stage.name(), stage.title(), remaining.size(), sum(remaining),
                    byReason.entrySet().stream()
                            .map(entry -> new FunnelDropDto(entry.getKey(), entry.getValue().size(), sum(entry.getValue())))
                            .toList()));
        }
        return stages;
    }

    /**
     * stage без reason - УПД, прошедшие ступень воронки (их число - на полосе ступени, ЭП-4);
     * stage с reason - отсеянные на этой ступени по этой причине (причина справа от полосы).
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public PageDto<RunUpdDto> upds(final long runId, final Long clientId, final Long buyerId, final Integer category,
                                   final String decision, final String reason, final String stage, final String riskLevel,
                                   final String paymentStatus, final int page, final int size) {
        final Predicate<RunUpdDto> filter = row -> (clientId == null || clientId.equals(row.client().id()))
                && (buyerId == null || row.buyer() != null && buyerId.equals(row.buyer().id()))
                && (category == null || category.equals(row.category()))
                && (decision == null || decision.equals(row.decision()))
                && (reason == null || reason.equals(row.reason()))
                && (stage == null || (reason != null ? stage.equals(row.stage()) : passed(row, stage)))
                && (riskLevel == null || row.riskLevel() != null && riskLevel.equals(row.riskLevel().name()))
                && (paymentStatus == null || paymentStatus.equals(row.paymentStatus()));
        final List<RunUpdDto> filtered = rows(runId, null).stream().filter(filter).toList();
        final int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        final int from = Math.min(Math.max(page, 0) * pageSize, filtered.size());
        return new PageDto<>(filtered.subList(from, Math.min(from + pageSize, filtered.size())), Math.max(page, 0), pageSize,
                filtered.size());
    }

    /**
     * Открытый УПД прошёл ступень: дошёл до очереди или отсеян на одной из следующих ступеней (как в {@link #funnel}).
     */
    private static boolean passed(final RunUpdDto row, final String stage) {
        if (row.stage() == null) {
            return false;
        }
        if (row.reason() == null) {
            return true;
        }
        return FunnelStage.valueOf(row.stage()).ordinal() > FunnelStage.valueOf(stage).ordinal();
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    public UpdTraceDto trace(final long runId, final long updId) {
        final RunUpdDto upd = rows(runId, updId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("УПД в прогоне " + runId, updId));
        final MapSqlParameterSource params = new MapSqlParameterSource().addValue("runId", runId).addValue("updId", updId);
        final List<TraceStepDto> steps = new ArrayList<>();

        final Map<String, Object> loaded = jdbc.queryForMap("""
                select u.first_loaded_run_id, u.contract_id, k.number as contract_number, k.deferral_days
                from upd u left join contract k on k.id = u.contract_id where u.id = :updId""", params);
        steps.add(new TraceStepDto(1, "Загрузка данных", "Загружен в прогоне " + loaded.get("first_loaded_run_id")
                + (upd.buyer() != null && upd.buyer().id() == null ? "; покупателя нет в хранилище внешних данных" : ""), loaded));

        final List<Map<String, Object>> allocations = jdbc.queryForList("""
                select p.source_id, p.payment_date, p.purpose, a.amount, a.rule
                from payment_allocation a join payment p on p.id = a.payment_id
                where a.run_id = :runId and a.upd_id = :updId order by p.payment_date, p.source_id""", params);
        steps.add(new TraceStepDto(2, "Сверка оплат", allocations.isEmpty() ? "Оплат нет"
                : "Оплачено " + upd.amount().subtract(upd.outstanding()) + " из " + upd.amount() + " (платежей: " + allocations.size() + ")",
                Map.of("allocations", allocations)));

        final Map<String, Object> category = new LinkedHashMap<>();
        category.put("plannedDueDate", upd.plannedDueDate());
        category.put("daysToDue", upd.daysToDue());
        category.put("paymentStatus", upd.paymentStatus());
        category.put("category", upd.category());
        steps.add(new TraceStepDto(3, "Категоризация дебиторки", upd.category() != null ? "Категория " + upd.category() : "Оплачен полностью",
                category));

        final List<Map<String, Object>> scores = jdbc.queryForList("""
                select bs.stop_factor, bs.has_payment_history, bs.pd, bs.pd_source, bs.low_data, bs.risk_level, bs.model_version as pd_model,
                       bs.data_date, s.status, s.p_delay, s.p_overdue_90, s.expected_delay_days, s.no_pair_history,
                       s.model_version as delay_model, s.error_message
                from upd u
                left join buyer_score bs on bs.run_id = :runId and bs.buyer_id = u.buyer_id
                left join upd_score s on s.run_id = :runId and s.upd_id = u.id
                where u.id = :updId""", params);
        final Map<String, Object> score = scores.isEmpty() ? Map.of() : scores.getFirst();
        steps.add(new TraceStepDto(4, "Скоринг покупателей", scoringSummary(upd, score), stringify(score)));

        final List<Map<String, Object>> decisions = jdbc.queryForList("select * from upd_decision where run_id = :runId and upd_id = :updId",
                params);
        steps.add(new TraceStepDto(5, "Решение и предложение", decisions.isEmpty() ? "Не рассматривался (оплачен)"
                : "OFFER".equals(upd.decision()) ? "Предложение: " + upd.eadFinal() + " руб." : "Нет предложения: " + upd.reason(),
                decisions.isEmpty() ? Map.of() : stringify(decisions.getFirst())));

        final List<RunLogEntryDto> log = jdbc.query("""
                        select * from run_log where upd_id = :updId and (run_id = :runId or run_id is null) order by id""", params,
                (rs, rowNum) -> new RunLogEntryDto(rs.getLong("id"), rs.getObject("logged_at", OffsetDateTime.class), rs.getString("level"),
                        rs.getObject("step_no", Integer.class), rs.getString("event_code"), rs.getString("message"),
                        rs.getObject("client_id", Long.class), rs.getObject("buyer_id", Long.class), rs.getObject("upd_id", Long.class),
                        rs.getObject("offer_id", Long.class), json.readMap(rs.getString("details"))));
        return new UpdTraceDto(upd, steps, log);
    }

    public List<RunBuyerDto> buyers(final long runId) {
        return jdbc.query("""
                        select bs.*, b.inn, b.name,
                               (select count(*) from upd_state us join upd u on u.id = us.upd_id
                                where us.run_id = bs.run_id and u.buyer_id = bs.buyer_id and us.payment_status <> 'PAID') as open_upds,
                               (select coalesce(sum(us.outstanding), 0) from upd_state us join upd u on u.id = us.upd_id
                                where us.run_id = bs.run_id and u.buyer_id = bs.buyer_id and us.payment_status <> 'PAID') as open_amount,
                               (select count(*) from upd_decision d join upd u on u.id = d.upd_id
                                where d.run_id = bs.run_id and u.buyer_id = bs.buyer_id and d.decision = 'OFFER') as offers,
                               (select coalesce(sum(d.ead_final), 0) from upd_decision d join upd u on u.id = d.upd_id
                                where d.run_id = bs.run_id and u.buyer_id = bs.buyer_id and d.decision = 'OFFER') as ead_total
                        from buyer_score bs join buyer b on b.id = bs.buyer_id
                        where bs.run_id = :runId order by b.name""", new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> new RunBuyerDto(rs.getLong("buyer_id"), rs.getString("inn"), rs.getString("name"), rs.getString("stop_factor"),
                        rs.getBoolean("has_payment_history"), rs.getBigDecimal("pd"), rs.getString("pd_source"), rs.getBoolean("low_data"),
                        level(rs.getString("risk_level")), json.read(rs.getString("reasons"), REASONS), rs.getString("model_version"),
                        rs.getObject("data_date", LocalDate.class), rs.getBoolean("from_cache"), rs.getInt("open_upds"),
                        rs.getBigDecimal("open_amount"), rs.getInt("offers"), rs.getBigDecimal("ead_total")));
    }

    public List<RunClientDto> clients(final long runId) {
        return jdbc.query("""
                        select c.id, c.inn, c.name, c.industry, c.is_core, c.abs_liquidity,
                               count(*) filter (where us.payment_status <> 'PAID') as open_upds,
                               coalesce(sum(us.outstanding) filter (where us.payment_status <> 'PAID'), 0) as open_amount,
                               count(*) filter (where us.category > 4) as overdue_upds,
                               count(d.upd_id) filter (where d.decision = 'OFFER') as offers,
                               coalesce(sum(d.ead_final) filter (where d.decision = 'OFFER'), 0) as ead_total
                        from upd_state us
                        join upd u on u.id = us.upd_id
                        join client c on c.id = u.client_id
                        left join upd_decision d on d.run_id = us.run_id and d.upd_id = us.upd_id
                        where us.run_id = :runId
                        group by c.id order by c.name""", new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> new RunClientDto(rs.getLong("id"), rs.getString("inn"), rs.getString("name"), rs.getString("industry"),
                        rs.getBoolean("is_core"), rs.getBigDecimal("abs_liquidity"), rs.getInt("open_upds"), rs.getBigDecimal("open_amount"),
                        rs.getInt("overdue_upds"), rs.getInt("offers"), rs.getBigDecimal("ead_total")));
    }

    private List<RunUpdDto> rows(final long runId, final Long updId) {
        requireRun(runId);
        final MapSqlParameterSource params = new MapSqlParameterSource().addValue("runId", runId).addValue("updId", updId);
        return jdbc.query(UPD_ROWS + (updId != null ? " and u.id = :updId" : "") + " order by c.name, u.upd_date, u.number", params,
                (rs, rowNum) -> {
                    final String reason = rs.getString("reason");
                    final String decision = rs.getString("decision");
                    final String status = rs.getString("payment_status");
                    final FunnelStage dropped = FunnelStage.droppedAt(reason, rs.getObject("limit_order") != null);
                    final String stage = "PAID".equals(status) ? null : dropped != null ? dropped.name() : "QUEUED";
                    return new RunUpdDto(rs.getLong("id"), rs.getString("number"), rs.getObject("upd_date", LocalDate.class),
                            new ClientRefDto(rs.getLong("client_id"), rs.getString("client_inn"), rs.getString("client_name")),
                            new ClientRefDto(rs.getObject("buyer_id", Long.class), rs.getString("buyer_inn"), rs.getString("buyer_name")),
                            rs.getBigDecimal("amount"), rs.getBigDecimal("outstanding"), status,
                            rs.getObject("planned_due_date", LocalDate.class), rs.getInt("days_to_due"), rs.getObject("category", Integer.class),
                            rs.getBigDecimal("pd"), level(rs.getString("risk_level")), rs.getBigDecimal("p_delay"),
                            rs.getBigDecimal("p_overdue_90"), decision, reason, stage, rs.getBigDecimal("ead_final"),
                            rs.getBigDecimal("income_final"));
                });
    }

    private void requireRun(final long runId) {
        final Integer count = jdbc.queryForObject("select count(*) from run where id = :id", new MapSqlParameterSource("id", runId),
                Integer.class);
        if (count == null || count == 0) {
            throw ApiException.notFound("Прогон", runId);
        }
    }

    private static String scoringSummary(final RunUpdDto upd, final Map<String, Object> score) {
        if (upd.buyer() == null || upd.buyer().id() == null) {
            return "Нет данных по покупателю: не оценивается";
        }
        if (score.get("stop_factor") != null) {
            return "Стоп-фактор: " + score.get("stop_factor");
        }
        final StringBuilder text = new StringBuilder();
        if (score.get("pd") != null) {
            text.append("Уровень ").append(score.get("risk_level")).append(", PD ").append(score.get("pd"));
        }
        if (score.get("status") != null) {
            text.append("; задержка: ").append(score.get("status"));
            if (score.get("p_delay") != null) {
                text.append(", P(>порога) ").append(score.get("p_delay")).append(", P(90+) ").append(score.get("p_overdue_90"));
            }
        }
        return text.isEmpty() ? "Модель задержки не применялась" : text.toString();
    }

    /**
     * Значения строки БД - в JSON-совместимые: даты и время строкой.
     */
    private static Map<String, Object> stringify(final Map<String, Object> row) {
        final Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key, value instanceof java.util.Date || value instanceof java.time.temporal.Temporal
                ? value.toString() : value));
        return result;
    }

    private static BigDecimal sum(final List<RunUpdDto> rows) {
        return rows.stream().map(RunUpdDto::outstanding).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static RiskLevel level(final String value) {
        return value != null ? RiskLevel.valueOf(value) : null;
    }
}
