package ru.smart.factoring.backend.factoring.decision;

import ru.smart.factoring.backend.factoring.parameter.ParameterService;
import ru.smart.factoring.backend.factoring.parameter.RunParameters;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Параметры Приложения А по умолчанию - как в 004-data.xml.
 */
final class TestParameters {

    private TestParameters() {
    }

    static RunParameters defaults() {
        final Map<String, String> raw = new LinkedHashMap<>();
        raw.put("run.max_duration_minutes", "10");
        raw.put("run.upd_retry_count", "2");
        raw.put("scoring.pd_revenue_threshold", "120000000");
        raw.put("scoring.delay_threshold_days", "14");
        raw.put("scoring.risk_level_bounds", "{\"A\": 0.01, \"B\": 0.03, \"C\": 0.08}");
        raw.put("decision.advance_rate", "{\"A\": 0.90, \"B\": 0.85, \"C\": 0.75}");
        raw.put("decision.lgd", "{\"A\": 0.40, \"B\": 0.45, \"C\": 0.50}");
        raw.put("decision.funding_rate", "0.16");
        raw.put("decision.opex_rate", "0.015");
        raw.put("decision.margin_rate", "0.03");
        raw.put("decision.market_rate", "0.24");
        raw.put("decision.day_count_basis", "365");
        raw.put("decision.delay_probability_threshold", "0.30");
        raw.put("decision.overdue_90_probability_threshold", "0.10");
        raw.put("decision.min_income", "3000");
        raw.put("decision.min_outstanding", "300000");
        raw.put("decision.min_days_to_due", "10");
        raw.put("decision.buyer_limit", "{\"A\": 30000000, \"B\": 20000000, \"C\": 10000000}");
        raw.put("ranking.tr_base", "0.07");
        raw.put("ranking.tr_multiplier", "1.5");
        raw.put("ranking.tr_liquidity_threshold", "0.2");
        raw.put("ranking.tr_deferral_days_threshold", "60");
        raw.put("ranking.offers_per_client", "3");
        raw.put("ranking.reminder_days", "3");
        raw.put("ranking.new_offer_notification_threshold", "1000000");
        raw.put("card.cash_gap_liquidity_threshold", "0.2");
        raw.put("comparison.manual_minutes_per_client", "200");
        raw.put("comparison.system_minutes_per_client", "20");
        raw.put("comparison.workday_hours", "8");
        return new ParameterService(null, new ru.smart.factoring.backend.factoring.json.Json(JsonMapper.builder().build())).fromRaw(raw);
    }
}
