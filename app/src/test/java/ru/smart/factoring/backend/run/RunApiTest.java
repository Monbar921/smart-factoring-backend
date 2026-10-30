package ru.smart.factoring.backend.run;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.factoring.run.RunService;
import ru.smart.factoring.backend.support.Api;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Каркас прогона (модуль 1): запуск, шаги, статусы, история, журнал, «один прогон за раз» (ПР-2, ПР-3, ПР-5, ПР-6).
 */
class RunApiTest extends AbstractSmartFactoringBackendTest {
    private static final long POLL_MS = 100;
    private static final int MAX_POLLS = 200;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RunService runService;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void rejectsRunWithoutSourceData() {
        final Api.Response response = Api.post("/runs", null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("code").asString()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void runsAllStepsAndShowsResult() throws InterruptedException {
        insertSourceUpd();

        final Api.Response defaults = Api.get("/runs/defaults");
        assertThat(defaults.body().get("dataDate").asString()).isEqualTo("2026-10-24");
        assertThat(defaults.body().get("clients")).hasSize(1);

        final Api.Response started = Api.post("/runs", Map.of());
        assertThat(started.status()).isEqualTo(202);
        final long runId = started.body().get("runId").asLong();

        final JsonNode run = awaitFinished(runId);
        assertThat(run.get("status").asString()).isEqualTo("DONE");
        assertThat(run.get("calcDate").asString()).isEqualTo("2026-10-24");
        assertThat(run.get("steps")).hasSize(5).allSatisfy(step -> assertThat(step.get("status").asString()).isEqualTo("DONE"));
        assertThat(run.get("steps").get(0).get("name").asString()).isEqualTo("Загрузка данных");

        final JsonNode history = Api.get("/runs").body();
        assertThat(history.get("total").asLong()).isEqualTo(1);
        assertThat(history.get("items").get(0).get("id").asLong()).isEqualTo(runId);

        final JsonNode log = Api.get("/runs/" + runId + "/log").body();
        assertThat(log.get("items")).extracting(entry -> entry.get("eventCode").asString())
                .contains("RUN_STARTED", "RUN_FINISHED");
        // тексты журнала - для человека: дата ДД.ММ.ГГГГ, статус по-русски
        assertThat(log.get("items")).extracting(entry -> entry.get("message").asString())
                .contains("Анализ дебиторки запущен, дата расчёта 24.10.2026")
                .anySatisfy(message -> assertThat(message).startsWith("Анализ дебиторки завершён: выполнен, "));
    }

    @Test
    void takesCalcDateAndClientsFromRequest() throws InterruptedException {
        insertSourceUpd();

        final long runId = Api.post("/runs", Map.of("clientInns", List.of("7710000001"), "calcDate", "2026-10-01"))
                .body().get("runId").asLong();

        final JsonNode run = awaitFinished(runId);
        assertThat(run.get("calcDate").asString()).isEqualTo("2026-10-01");
        assertThat(run.get("clientInns").get(0).asString()).isEqualTo("7710000001");
    }

    @Test
    void rejectsUnknownClient() {
        insertSourceUpd();

        final Api.Response response = Api.post("/runs", Map.of("clientInns", List.of("0000000000")));

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void rejectsSecondRunWhileRunning() {
        insertSourceUpd();
        jdbcTemplate.update("insert into run (status, calc_date, initiator_type, parameters) values ('RUNNING', date '2026-10-24', 'API', '{}')");

        final Api.Response response = Api.post("/runs", null);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("code").asString()).isEqualTo("RUN_ALREADY_RUNNING");
        assertThat(response.body().get("message").asString()).startsWith("Анализ дебиторки уже идёт: запуск № ");
        assertThat(attempts()).containsExactly("REJECTED RUN_ALREADY_RUNNING null");
    }

    @Test
    void sameDataIsNotRunAgainWithoutConfirmation() throws InterruptedException {
        insertSourceUpd();
        final long first = Api.post("/runs", Map.of()).body().get("runId").asLong();
        awaitFinished(first);

        // П4-3: тот же отпечаток - отказ со ссылкой на прежний результат
        final Api.Response again = Api.post("/runs", Map.of());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("code").asString()).isEqualTo("RUN_ALREADY_DONE");
        assertThat(again.body().get("message").asString()).startsWith("Эти данные уже проанализированы. Результат — запуск № " + first + " от ");
        assertThat(again.body().get("details").get("runId").asLong()).isEqualTo(first);

        // «Прогнать заново» с подтверждением
        final Api.Response forced = Api.post("/runs", Map.of("force", true));
        assertThat(forced.status()).isEqualTo(202);
        awaitFinished(forced.body().get("runId").asLong());

        // данные изменились - другой отпечаток, запуск без вопросов
        jdbcTemplate.update("""
                insert into manual_upload_upd (client_inn, buyer_inn, contract_number, number, upd_date, amount)
                values ('7710000001', '7720000002', 'Д-1', 'U-2', date '2026-10-24', 2000000)""");
        final Api.Response changed = Api.post("/runs", Map.of());
        assertThat(changed.status()).isEqualTo(202);
        awaitFinished(changed.body().get("runId").asLong());

        assertThat(attempts()).containsExactly("STARTED null null", "STARTED null null", "REJECTED RUN_ALREADY_DONE null",
                "STARTED null null");
    }

    @Test
    void guestCannotStartRun() {
        insertSourceUpd();

        // П4-1: роли в токене есть, но токен гостевого входа - только просмотр
        final Api.Response response = Api.postAs("/runs", Map.of(), """
                {"preferred_username":"demo","azp":"smart-factoring-guest","realm_access":{"roles":["operator","manager"]}}""");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body().get("message").asString()).isEqualTo("Запуск анализа дебиторки доступен менеджеру");
        assertThat(jdbcTemplate.queryForObject("select count(*) from run", Long.class)).isZero();
        assertThat(attempts()).containsExactly("REJECTED FORBIDDEN гость");
    }

    @Test
    void historyShowsWhoStartedRun() throws InterruptedException {
        insertSourceUpd();

        final Api.Response started = Api.postAs("/runs", Map.of(), """
                {"preferred_username":"ivanov","azp":"smart-factoring-ui","realm_access":{"roles":["manager","operator","offline_access"]}}""");
        awaitFinished(started.body().get("runId").asLong());

        final JsonNode item = Api.get("/runs").body().get("items").get(0);
        assertThat(item.get("initiator").asString()).isEqualTo("ivanov");
        assertThat(item.get("initiatorRole").asString()).isEqualTo("manager, operator");
        assertThat(Api.get("/runs/attempts").body().get("items").get(0).get("initiator").asString()).isEqualTo("ivanov");
    }

    /**
     * Журнал попыток запуска (П4-6), новые сверху: «исход причина роль».
     */
    private List<String> attempts() {
        final List<String> rows = new java.util.ArrayList<>();
        Api.get("/runs/attempts").body().get("items").forEach(item -> rows.add(item.get("outcome").asString() + " "
                + text(item.get("reason")) + " " + text(item.get("initiatorRole"))));
        return rows;
    }

    private static String text(final JsonNode node) {
        return node == null || node.isNull() ? "null" : node.asString();
    }

    @Test
    void stopsRunInterruptedByRestart() {
        jdbcTemplate.update("insert into run (status, calc_date, initiator_type, parameters) values ('RUNNING', date '2026-10-24', 'API', '{}')");

        runService.stopInterruptedRuns();

        assertThat(jdbcTemplate.queryForObject("select status from run", String.class)).isEqualTo("STOPPED");
    }

    @Test
    void returnsNotFoundForUnknownRun() {
        assertThat(Api.get("/runs/999").status()).isEqualTo(404);
    }

    private void insertSourceUpd() {
        jdbcTemplate.update("""
                insert into manual_upload_client (inn, name, industry, abs_liquidity, manager_code)
                values ('7710000001', 'Клиент', 'TRADE', 0.3, 'M1')""");
        jdbcTemplate.update("""
                insert into manual_upload_upd (client_inn, buyer_inn, contract_number, number, upd_date, amount)
                values ('7710000001', '7720000002', 'Д-1', 'U-1', date '2026-10-24', 1000000)""");
    }

    private JsonNode awaitFinished(final long runId) throws InterruptedException {
        for (int i = 0; i < MAX_POLLS; i++) {
            final JsonNode run = Api.get("/runs/" + runId).body();
            if (!"RUNNING".equals(run.get("status").asString())) {
                return run;
            }
            Thread.sleep(POLL_MS);
        }
        throw new AssertionError("Прогон " + runId + " не завершился");
    }
}
