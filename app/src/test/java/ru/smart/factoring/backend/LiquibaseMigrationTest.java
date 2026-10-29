package ru.smart.factoring.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import ru.smart.factoring.backend.support.TestDatabase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Миграции Liquibase накатываются, ограничения схемы работают (docs/claude/module-0/data-model.md).
 * Каждый тест в своей транзакции с откатом: контейнер Postgres переиспользуется между запусками.
 */
@Transactional
class LiquibaseMigrationTest extends AbstractSmartFactoringBackendTest {

    private static final String SCHEMA = "smart_factoring_test";

    private static final List<String> MANUAL_UPLOAD_TABLES = List.of(
            "manual_upload_batch", "manual_upload_manager", "manual_upload_client", "manual_upload_buyer",
            "manual_upload_buyer_financials", "manual_upload_contract", "manual_upload_upd", "manual_upload_payment",
            "manual_upload_egrul_status", "manual_upload_bankruptcy_case", "manual_upload_efrsb_event",
            "imitation_factoring_package", "imitation_risk_signal");

    private static final List<String> MAIN_TABLES = List.of(
            "manager", "client", "buyer", "buyer_external_data", "buyer_financials", "contract", "upd", "payment",
            "run", "run_step", "run_log", "payment_allocation", "upd_state", "buyer_score", "buyer_pd_cache",
            "upd_score", "upd_decision", "offer", "notification", "llm_text_cache", "parameter");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsManualUploadAndMainTables() {
        assertThat(tables()).containsAll(MANUAL_UPLOAD_TABLES).containsAll(MAIN_TABLES);
    }

    @Test
    void leavesNoDemoNames() {
        // Р-56: таблицы, ограничения, индексы и последовательности demo_* переименованы
        assertThat(jdbcTemplate.queryForList("""
                select cl.relname from pg_class cl join pg_namespace n on n.oid = cl.relnamespace
                where n.nspname = ? and cl.relname like 'demo\\_%'
                union all
                select c.conname from pg_constraint c join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conname like 'demo\\_%'""", String.class, SCHEMA, SCHEMA)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from pg_constraint where conname = 'manual_upload_batch_kind_check'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void fillsParameterDefaults() {
        assertThat(jdbcTemplate.queryForObject("select value from parameter where code = 'decision.funding_rate'", String.class))
                .isEqualTo("0.16");
        assertThat(jdbcTemplate.queryForObject("select value from parameter where code = 'ranking.offers_per_client'", String.class))
                .isEqualTo("3");
        assertThat(jdbcTemplate.queryForObject("select count(*) from parameter where value_type = 'JSON' and value::jsonb is null",
                Integer.class)).isZero();
    }

    @Test
    void riskLevelBoundsAreModelBounds() {
        // Р-65: A до 0,4 %, B до 1,5 %, C до 6 %, выше - D (как risk_level в данных ML-команды)
        TestDatabase.clean(jdbcTemplate);
        assertThat(jdbcTemplate.queryForObject("select value from parameter where code = 'scoring.risk_level_bounds'", String.class))
                .isEqualTo("{\"A\": 0.004, \"B\": 0.015, \"C\": 0.06}");
    }

    @Test
    void rejectsSecondRunningRun() {
        insertRun("DONE");
        insertRun("RUNNING");

        assertThatThrownBy(() -> insertRun("RUNNING")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsOnlyOneActiveOfferPerUpd() {
        final long runId = insertRun("RUNNING");
        final long managerId = insert("insert into manager (code, full_name) values ('m1', 'Менеджер') returning id");
        final long clientId = insert("""
                insert into client (inn, name, industry, manager_id, edo_consent, factoring_client)
                values ('7700000001', 'Клиент', 'TRADE', ?, true, false) returning id""", managerId);
        final long buyerId = insert("insert into buyer (inn, name) values ('7700000002', 'Покупатель') returning id");
        final long updId = insert("""
                insert into upd (client_id, buyer_id, buyer_inn, number, upd_date, amount)
                values (?, ?, '7700000002', 'УПД-1', date '2026-09-01', 1000000) returning id""", clientId, buyerId);

        insertOffer(updId, clientId, buyerId, managerId, runId, "REVOKED");
        insertOffer(updId, clientId, buyerId, managerId, runId, "QUEUED");

        assertThatThrownBy(() -> insertOffer(updId, clientId, buyerId, managerId, runId, "QUEUED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateUpdFromSource() {
        final String sql = """
                insert into manual_upload_upd (client_inn, buyer_inn, contract_number, number, upd_date, amount)
                values ('7700000001', '7700000002', 'Д-1', 'УПД-1', date '2026-09-01', 1000000)""";
        jdbcTemplate.update(sql);

        assertThatThrownBy(() -> jdbcTemplate.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> tables() {
        return jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = ?", String.class, SCHEMA);
    }

    private long insertRun(final String status) {
        return insert("""
                insert into run (status, calc_date, initiator_type, parameters)
                values (?, date '2026-10-24', 'OPERATOR', '{}') returning id""", status);
    }

    private void insertOffer(final long updId, final long clientId, final long buyerId, final long managerId,
                             final long runId, final String status) {
        jdbcTemplate.update("""
                insert into offer (upd_id, client_id, buyer_id, manager_id, status, revoke_reason, created_run_id, last_run_id,
                                   ead, price, price_rate, term_days, income, el, risk_level, pd, rank_value)
                values (?, ?, ?, ?, ?, case when ? = 'REVOKED' then 'UPD_PAID' end, ?, ?,
                        850000, 23400, 0.2185, 46, 3200, 1400, 'B', 0.03, 336)""",
                updId, clientId, buyerId, managerId, status, status, runId, runId);
    }

    private long insert(final String sql, final Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }
}
