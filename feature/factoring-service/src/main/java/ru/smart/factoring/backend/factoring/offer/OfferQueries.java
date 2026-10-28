package ru.smart.factoring.backend.factoring.offer;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.api.common.ClientRefDto;
import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.api.crm.OfferDto;
import ru.smart.factoring.backend.api.crm.OfferFlagsDto;
import ru.smart.factoring.backend.api.crm.ReasonDto;
import ru.smart.factoring.backend.factoring.json.Json;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Чтение предложений в формате карточки (п. 5.3, пункт 4).
 */
@Component
@RequiredArgsConstructor
public class OfferQueries {
    private static final TypeReference<List<ReasonDto>> REASONS = new TypeReference<>() {
    };
    private static final String SELECT = """
            select o.*, u.number as upd_number, u.upd_date, c.inn as client_inn, c.name as client_name,
                   b.inn as buyer_inn, b.name as buyer_name, d.tr, s.p_delay, s.delay_threshold_days,
                   (select us.outstanding from upd_state us where us.run_id = o.last_run_id and us.upd_id = o.upd_id) as upd_outstanding
            from offer o
            join upd u on u.id = o.upd_id
            join client c on c.id = o.client_id
            join buyer b on b.id = o.buyer_id
            left join upd_decision d on d.run_id = o.last_run_id and d.upd_id = o.upd_id
            left join upd_score s on s.run_id = o.last_run_id and s.upd_id = o.upd_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

    public Optional<OfferDto> byId(final long offerId) {
        return jdbc.query(SELECT + " where o.id = :id", new MapSqlParameterSource("id", offerId), mapper()).stream().findFirst();
    }

    /**
     * Очередь менеджера: предложения «в очереди» по убыванию ранга (п. 5.3, пункт 2).
     */
    public List<OfferDto> queue(final long managerId) {
        return jdbc.query(SELECT + " where o.manager_id = :managerId and o.status = 'QUEUED' order by o.rank_value desc, u.upd_date, u.number",
                new MapSqlParameterSource("managerId", managerId), mapper());
    }

    /**
     * Предложения клиента: в очереди - сначала новые в прогоне, затем по рангу (п. 5.5); остальные - по дате решения.
     */
    public List<OfferDto> byClient(final long clientId, final List<String> statuses) {
        return jdbc.query(SELECT + """
                         where o.client_id = :clientId and o.status in (:statuses)
                         order by o.is_new desc, o.rank_value desc, o.decided_at desc nulls last, o.id""",
                new MapSqlParameterSource().addValue("clientId", clientId).addValue("statuses", statuses), mapper());
    }

    private RowMapper<OfferDto> mapper() {
        return (rs, rowNum) -> {
            final Map<String, Object> flags = json.readMap(rs.getString("flags"));
            return new OfferDto(rs.getLong("id"), rs.getString("status"), rs.getLong("upd_id"), rs.getString("upd_number"),
                    rs.getObject("upd_date", LocalDate.class), rs.getBigDecimal("upd_outstanding"),
                    new ClientRefDto(rs.getLong("client_id"), rs.getString("client_inn"), rs.getString("client_name")),
                    new ClientRefDto(rs.getLong("buyer_id"), rs.getString("buyer_inn"), rs.getString("buyer_name")),
                    rs.getBigDecimal("ead"), rs.getBigDecimal("price"), rs.getBigDecimal("price_rate"), rs.getInt("term_days"),
                    RiskLevel.valueOf(rs.getString("risk_level")), rs.getBigDecimal("pd"), rs.getBigDecimal("income"),
                    rs.getBigDecimal("el"), rs.getBigDecimal("tr"), rs.getBigDecimal("rank_value"),
                    new OfferFlagsDto(flag(flags, "delayProbable"), flag(flags, "lowData"), flag(flags, "reducedToLimit"),
                            rs.getBoolean("is_new")),
                    json.read(rs.getString("reasons"), REASONS), rs.getString("reasons_text"), rs.getString("message_draft"),
                    rs.getObject("queued_at", OffsetDateTime.class), rs.getLong("last_run_id"), rs.getString("revoke_reason"),
                    rs.getString("decided_by"), rs.getObject("decided_at", OffsetDateTime.class), rs.getString("text_source"),
                    rs.getBigDecimal("p_delay"), rs.getObject("delay_threshold_days", Integer.class));
        };
    }

    private static boolean flag(final Map<String, Object> flags, final String name) {
        return flags != null && Boolean.TRUE.equals(flags.get(name));
    }
}
