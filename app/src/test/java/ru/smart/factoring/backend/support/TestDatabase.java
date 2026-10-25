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
            "demo_import_batch", "demo_manager", "demo_client", "demo_buyer", "demo_buyer_financials", "demo_contract",
            "demo_upd", "demo_payment", "demo_egrul_status", "demo_bankruptcy_case", "demo_efrsb_event",
            "demo_factoring_package", "demo_risk_signal");

    private TestDatabase() {
    }

    public static void clean(final JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("truncate table " + String.join(", ", TABLES) + " restart identity cascade");
    }
}
