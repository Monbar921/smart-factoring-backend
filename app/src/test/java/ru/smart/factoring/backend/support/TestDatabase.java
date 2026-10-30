package ru.smart.factoring.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Очистка данных между интеграционными тестами: контейнер Postgres и контекст Spring общие для всех тестов.
 * Параметры (parameter) не трогаются, кроме границ уровней риска: их меняет эталонная фикстура (см. {@link #useFixtureRiskBounds}),
 * очистка возвращает значение по умолчанию. Служебные таблицы Liquibase не трогаются.
 */
public final class TestDatabase {
    private static final List<String> TABLES = List.of(
            "run_log", "notification", "offer", "upd_decision", "upd_score", "buyer_score", "buyer_pd_cache", "upd_state",
            "payment_allocation", "run_step", "payment", "upd", "contract", "buyer_financials", "buyer_external_data",
            "client", "buyer", "manager", "run_attempt", "run", "llm_text_cache",
            "manual_upload_batch", "manual_upload_manager", "manual_upload_client", "manual_upload_buyer", "manual_upload_buyer_financials", "manual_upload_contract",
            "manual_upload_upd", "manual_upload_payment", "manual_upload_egrul_status", "manual_upload_bankruptcy_case", "manual_upload_efrsb_event",
            "imitation_factoring_package", "imitation_risk_signal");

    /** границы уровней по умолчанию - как у модели PD (Р-65) */
    public static final String DEFAULT_RISK_BOUNDS = "{\"A\": 0.004, \"B\": 0.015, \"C\": 0.06}";
    /** границы, под которые посчитаны случаи эталонной фикстуры fixture-v1 и reference-sim (CASES.md) */
    public static final String FIXTURE_RISK_BOUNDS = "{\"A\": 0.01, \"B\": 0.03, \"C\": 0.08}";
    private static final String RISK_BOUNDS = "update parameter set value = ? where code = 'scoring.risk_level_bounds'";

    private TestDatabase() {
    }

    public static void clean(final JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("truncate table " + String.join(", ", TABLES) + " restart identity cascade");
        jdbcTemplate.update(RISK_BOUNDS, DEFAULT_RISK_BOUNDS);
    }

    /**
     * Эталонная фикстура задаёт свои границы уровней: её ожидаемые уровни, авансы, лимиты и суммы (К-1…К-18)
     * посчитаны при 1/3/8 %, а по умолчанию границы - 0,4/1,5/6 % (Р-65).
     */
    public static void useFixtureRiskBounds(final JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(RISK_BOUNDS, FIXTURE_RISK_BOUNDS);
    }
}
