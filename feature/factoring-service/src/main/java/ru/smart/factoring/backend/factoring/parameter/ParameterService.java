package ru.smart.factoring.backend.factoring.parameter;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.factoring.json.Json;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Параметры Приложения А из таблицы parameter.
 */
@Service
@RequiredArgsConstructor
public class ParameterService {
    private static final TypeReference<Map<String, BigDecimal>> LEVEL_MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final Json json;

    public List<ParameterRow> findAll() {
        return jdbc.sql("select code, value, value_type, section, description from parameter order by section, code")
                .query(ParameterRow.class)
                .list();
    }

    public RunParameters load() {
        final Map<String, String> raw = new LinkedHashMap<>();
        findAll().forEach(row -> raw.put(row.code(), row.value()));
        return fromRaw(raw);
    }

    /**
     * Параметры из снимка прогона (run.parameters): детализация идёт по тем же значениям, что прогон.
     */
    public RunParameters fromRaw(final Map<String, String> raw) {
        return new RunParameters(
                Map.copyOf(raw),
                integer(raw, "run.max_duration_minutes"),
                integer(raw, "run.upd_retry_count"),
                decimal(raw, "scoring.pd_revenue_threshold"),
                integer(raw, "scoring.delay_threshold_days"),
                levels(raw, "scoring.risk_level_bounds"),
                levels(raw, "decision.advance_rate"),
                levels(raw, "decision.lgd"),
                decimal(raw, "decision.funding_rate"),
                decimal(raw, "decision.opex_rate"),
                decimal(raw, "decision.margin_rate"),
                decimal(raw, "decision.market_rate"),
                integer(raw, "decision.day_count_basis"),
                decimal(raw, "decision.delay_probability_threshold"),
                decimal(raw, "decision.overdue_90_probability_threshold"),
                decimal(raw, "decision.min_income"),
                decimal(raw, "decision.min_outstanding"),
                integer(raw, "decision.min_days_to_due"),
                levels(raw, "decision.buyer_limit"),
                decimal(raw, "ranking.tr_base"),
                decimal(raw, "ranking.tr_multiplier"),
                decimal(raw, "ranking.tr_liquidity_threshold"),
                integer(raw, "ranking.tr_deferral_days_threshold"),
                integer(raw, "ranking.offers_per_client"),
                integer(raw, "ranking.reminder_days"),
                decimal(raw, "ranking.new_offer_notification_threshold"),
                decimal(raw, "card.cash_gap_liquidity_threshold"),
                integer(raw, "comparison.manual_minutes_per_client"),
                integer(raw, "comparison.system_minutes_per_client"),
                integer(raw, "comparison.workday_hours"));
    }

    private static String value(final Map<String, String> raw, final String code) {
        final String value = raw.get(code);
        if (value == null) {
            throw new IllegalStateException("Нет параметра " + code);
        }
        return value.trim();
    }

    private static int integer(final Map<String, String> raw, final String code) {
        return Integer.parseInt(value(raw, code));
    }

    private static BigDecimal decimal(final Map<String, String> raw, final String code) {
        return new BigDecimal(value(raw, code));
    }

    private Map<RiskLevel, BigDecimal> levels(final Map<String, String> raw, final String code) {
        final Map<RiskLevel, BigDecimal> result = new EnumMap<>(RiskLevel.class);
        json.read(value(raw, code), LEVEL_MAP).forEach((level, value) -> result.put(RiskLevel.valueOf(level), value));
        return result;
    }

    public record ParameterRow(String code, String value, String valueType, String section, String description) {
    }
}
