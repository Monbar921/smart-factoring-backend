package ru.smart.factoring.backend.factoring.offer;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ru.smart.factoring.backend.api.common.ClientRefDto;
import ru.smart.factoring.backend.api.common.PageDto;
import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.api.crm.CardUpdDto;
import ru.smart.factoring.backend.api.crm.ClientCardDto;
import ru.smart.factoring.backend.api.crm.ClientSummaryDto;
import ru.smart.factoring.backend.api.crm.ManagerDto;
import ru.smart.factoring.backend.api.crm.NotificationDto;
import ru.smart.factoring.backend.api.crm.OfferDto;
import ru.smart.factoring.backend.api.crm.ReasonDto;
import ru.smart.factoring.backend.factoring.error.ApiException;
import ru.smart.factoring.backend.factoring.json.Json;
import ru.smart.factoring.backend.factoring.model.Categories;
import ru.smart.factoring.backend.factoring.parameter.ParameterService;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Эмулятор CRM (ЭП-7): менеджеры, очередь, уведомления и карточка клиента так, как их получает CRM по API (п. 5.5).
 * Уведомления - из последнего успешного прогона (Р-33).
 */
@Service
@RequiredArgsConstructor
public class CrmService {
    private static final TypeReference<List<ReasonDto>> REASONS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final int MAX_PAGE_SIZE = 500;
    private static final String LAST_SUCCESSFUL_RUN = "(select max(id) from run where status in ('DONE', 'DONE_WITH_WARNINGS'))";

    private final NamedParameterJdbcTemplate jdbc;
    private final OfferQueries offers;
    private final ParameterService parameterService;
    private final Json json;

    public List<ManagerDto> managers() {
        return jdbc.query("""
                        select m.id, m.code, m.full_name,
                               (select count(*) from offer o where o.manager_id = m.id and o.status = 'QUEUED') as queued,
                               (select count(*) from notification n where n.manager_id = m.id and n.run_id = %s) as notifications
                        from manager m order by m.full_name""".formatted(LAST_SUCCESSFUL_RUN), new MapSqlParameterSource(),
                (rs, rowNum) -> new ManagerDto(rs.getLong("id"), rs.getString("code"), rs.getString("full_name"), rs.getInt("queued"),
                        rs.getInt("notifications")));
    }

    public List<OfferDto> queue(final long managerId) {
        requireManager(managerId);
        return offers.queue(managerId);
    }

    public PageDto<NotificationDto> notifications(final long managerId, final String type, final int page, final int size) {
        requireManager(managerId);
        final int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("managerId", managerId)
                .addValue("type", type)
                .addValue("size", pageSize)
                .addValue("offset", (long) Math.max(page, 0) * pageSize);
        final String where = " where n.manager_id = :managerId and n.run_id = " + LAST_SUCCESSFUL_RUN
                + " and (cast(:type as varchar) is null or n.type = :type)";
        final List<NotificationDto> items = jdbc.query("""
                        select n.*, c.inn as client_inn, c.name as client_name, b.inn as buyer_inn, b.name as buyer_name, u.number as upd_number
                        from notification n
                        left join client c on c.id = n.client_id
                        left join buyer b on b.id = n.buyer_id
                        left join upd u on u.id = n.upd_id""" + where + " order by n.id limit :size offset :offset", params,
                (rs, rowNum) -> new NotificationDto(rs.getLong("id"), rs.getLong("run_id"), rs.getString("type"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        ref(rs.getObject("client_id", Long.class), rs.getString("client_inn"), rs.getString("client_name")),
                        ref(rs.getObject("buyer_id", Long.class), rs.getString("buyer_inn"), rs.getString("buyer_name")),
                        rs.getObject("upd_id", Long.class), rs.getString("upd_number"), rs.getObject("offer_id", Long.class),
                        json.readMap(rs.getString("payload"))));
        final Long total = jdbc.queryForObject("select count(*) from notification n" + where, params, Long.class);
        return new PageDto<>(items, Math.max(page, 0), pageSize, total != null ? total : 0);
    }

    /**
     * Карточка клиента по последнему успешному прогону, в котором он был (п. 5.5).
     */
    public ClientCardDto card(final long clientId) {
        final Map<String, Object> client = jdbc.query("select id, inn, name, abs_liquidity from client where id = :id",
                new MapSqlParameterSource("id", clientId), (rs, rowNum) -> Map.<String, Object>of(
                        "inn", rs.getString("inn"), "name", rs.getString("name"),
                        "liquidity", rs.getBigDecimal("abs_liquidity") != null ? rs.getBigDecimal("abs_liquidity") : BigDecimal.ZERO))
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Клиент", clientId));
        final ClientRefDto ref = new ClientRefDto(clientId, (String) client.get("inn"), (String) client.get("name"));
        final BigDecimal liquidity = (BigDecimal) client.get("liquidity");
        final List<OfferDto> queued = offers.byClient(clientId, List.of("QUEUED"));
        final List<OfferDto> decided = offers.byClient(clientId, List.of("TRANSFERRED", "REJECTED"));

        final MapSqlParameterSource params = new MapSqlParameterSource("clientId", clientId);
        final Long runId = jdbc.queryForObject("""
                select max(us.run_id) from upd_state us
                join upd u on u.id = us.upd_id
                join run r on r.id = us.run_id and r.status in ('DONE', 'DONE_WITH_WARNINGS')
                where u.client_id = :clientId""", params, Long.class);
        if (runId == null) {
            return new ClientCardDto(ref, liquidity, null, null, null, null,
                    new ClientSummaryDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null), queued, decided, List.of());
        }
        params.addValue("runId", runId);
        final Map<String, Object> run = jdbc.queryForMap(
                "select calc_date, data_date, snapshot_date, parameters::text as parameters from run where id = :runId", params);
        final List<CardUpdDto> upds = jdbc.query("""
                        select u.id, u.number, u.upd_date, u.amount, b.id as buyer_id, coalesce(b.inn, u.buyer_inn) as buyer_inn,
                               b.name as buyer_name,
                               us.outstanding, us.planned_due_date, us.days_to_due, us.category,
                               bs.pd, bs.risk_level, bs.reasons, s.p_delay, s.p_overdue_90, s.expected_delay_days,
                               d.reason,
                               (select o.id from offer o where o.upd_id = u.id and o.status = 'QUEUED') as offer_id
                        from upd_state us
                        join upd u on u.id = us.upd_id
                        left join buyer b on b.id = u.buyer_id
                        left join buyer_score bs on bs.run_id = us.run_id and bs.buyer_id = u.buyer_id
                        left join upd_score s on s.run_id = us.run_id and s.upd_id = us.upd_id
                        left join upd_decision d on d.run_id = us.run_id and d.upd_id = us.upd_id
                        where us.run_id = :runId and u.client_id = :clientId and us.payment_status <> 'PAID'
                        order by us.category, us.planned_due_date, u.number""", params,
                (rs, rowNum) -> new CardUpdDto(rs.getLong("id"), rs.getString("number"), rs.getObject("upd_date", LocalDate.class),
                        ref(rs.getObject("buyer_id", Long.class), rs.getString("buyer_inn"), rs.getString("buyer_name")),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("outstanding"), rs.getObject("planned_due_date", LocalDate.class),
                        rs.getInt("days_to_due"), rs.getObject("category", Integer.class), rs.getBigDecimal("pd"),
                        rs.getString("risk_level") != null ? RiskLevel.valueOf(rs.getString("risk_level")) : null,
                        rs.getBigDecimal("p_delay"), rs.getBigDecimal("p_overdue_90"), rs.getBigDecimal("expected_delay_days"),
                        json.read(rs.getString("reasons"), REASONS), rs.getObject("offer_id", Long.class),
                        rs.getObject("offer_id", Long.class) == null ? rs.getString("reason") : null));

        final BigDecimal receivables = upds.stream().map(CardUpdDto::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        final BigDecimal overdue = upds.stream().filter(upd -> upd.category() != null && upd.category() > Categories.LAST_FINANCEABLE)
                .map(CardUpdDto::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        final BigDecimal available = queued.stream().map(OfferDto::ead).reduce(BigDecimal.ZERO, BigDecimal::add);
        final BigDecimal threshold = parameterService.fromRaw(json.read((String) run.get("parameters"), STRING_MAP))
                .cashGapLiquidityThreshold();
        final BigDecimal cashGap = overdue.signum() > 0 && liquidity.compareTo(threshold) < 0 ? overdue : null;
        return new ClientCardDto(ref, liquidity, runId, date(run.get("calc_date")), date(run.get("data_date")),
                date(run.get("snapshot_date")), new ClientSummaryDto(receivables, overdue, available, cashGap), queued, decided, upds);
    }

    private void requireManager(final long managerId) {
        final Integer count = jdbc.queryForObject("select count(*) from manager where id = :id", new MapSqlParameterSource("id", managerId),
                Integer.class);
        if (count == null || count == 0) {
            throw ApiException.notFound("Менеджер", managerId);
        }
    }

    private static ClientRefDto ref(final Long id, final String inn, final String name) {
        return inn == null && id == null ? null : new ClientRefDto(id, inn, name);
    }

    private static LocalDate date(final Object value) {
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return value instanceof LocalDate localDate ? localDate : null;
    }
}
