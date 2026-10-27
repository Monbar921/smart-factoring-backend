package ru.smart.factoring.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Очистка данных между интеграционными тестами: контейнер Postgres и контекст Spring общие для всех тестов.
 * Параметры (parameter) и служебные таблицы Liquibase не трогаются.
 */
public final class TestDatabase {
    private static final List<String> TABLES = List.of(
            "run_log", "notification", "offer", "upd_decision", "upd_score", "buyer_score", "buyer_pd_cache", "upd_state",
            "payment_allocation", "run_step", "payment", "upd", "contract", "buyer_financials", "buyer_external_data",
            "client", "buyer", "manager", "run", "llm_text_cache",
            "manual_upload_batch", "manual_upload_manager", "manual_upload_client", "manual_upload_buyer", "manual_upload_buyer_financials", "manual_upload_contract",
            "manual_upload_upd", "manual_upload_payment", "manual_upload_egrul_status", "manual_upload_bankruptcy_case", "manual_upload_efrsb_event",
            "imitation_factoring_package", "imitation_risk_signal");

    private TestDatabase() {
    }

    public static void clean(final JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("truncate table " + String.join(", ", TABLES) + " restart identity cascade");
    }
}
