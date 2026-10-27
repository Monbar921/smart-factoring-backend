package ru.smart.factoring.backend.load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.support.Api;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Модуль 2: импорт демо-данных и блок 1 «Загрузка данных» на фикстуре fixture-v1 (CASES.md).
 */
class DemoImportAndLoadTest extends AbstractSmartFactoringBackendTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        Fixture.importFixture(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void importsFixtureWithEvents() {
        assertThat(count("demo_client")).isEqualTo(8);
        assertThat(count("demo_upd")).isEqualTo(39);
        assertThat(count("demo_egrul_status")).isEqualTo(3);
        assertThat(count("demo_efrsb_event")).isEqualTo(92);
        assertThat(jdbcTemplate.queryForObject("select annulled from demo_efrsb_event limit 1", Boolean.class)).isNotNull();

        final JsonNode defaults = Api.get("/runs/defaults").body();
        assertThat(defaults.get("dataDate").asString()).isEqualTo("2026-10-24");
        assertThat(defaults.get("snapshotDate").asString()).isEqualTo("2026-10-20");
        // клиент факторинга и клиент без согласия на ЭДО не загружаются (сноска к п. 1.1)
        assertThat(defaults.get("clients")).hasSize(6);
    }

    @Test
    void rejectsInvalidExport() {
        final Api.Response response = Api.post("/demo/import", Map.of("path", "invalid-v1"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("code").asString()).isEqualTo("IMPORT_INVALID");
        assertThat(response.body().get("details").get("errors").toString())
                .contains("нет договора Д-999")
                .contains("industry должен быть")
                .contains("data_date 2026-10-01 не равна последней дате");
        // неудачный импорт ничего не меняет
        assertThat(count("demo_upd")).isEqualTo(39);
    }

    @Test
    void rejectsPathOutsideImportDir() {
        assertThat(Api.post("/demo/import", Map.of("path", "../../../pom.xml")).status()).isEqualTo(400);
    }

    @Test
    void loadsDataForRun() throws InterruptedException {
        final JsonNode run = Fixture.run(Map.of());

        assertThat(run.get("status").asString()).isEqualTo("DONE");
        final JsonNode metrics = Fixture.metrics(run, 1);
        assertThat(metrics.get("clients").asInt()).isEqualTo(6);
        assertThat(metrics.get("upds").asInt()).isEqualTo(37);
        assertThat(metrics.get("newUpds").asInt()).isEqualTo(37);
        assertThat(metrics.get("payments").asInt()).isEqualTo(13);
        assertThat(metrics.get("buyersWithoutData").asInt()).isEqualTo(1);
        assertThat(metrics.get("dataDate").asString()).isEqualTo("2026-10-24");
        assertThat(metrics.get("snapshotDate").asString()).isEqualTo("2026-10-20");

        // К-17: УПД покупателя вне реестра загружен без ссылки на покупателя
        assertThat(jdbcTemplate.queryForObject("select buyer_id from upd where number = 'U-1301'", Long.class)).isNull();
        // внешние данные: стоп-факторы реальных покупателей из events.csv
        assertThat(jdbcTemplate.queryForObject("""
                select e.bankruptcy_stage from buyer_external_data e join buyer b on b.id = e.buyer_id
                where b.inn = '0225011072'""", String.class)).isEqualTo("Конкурсное производство");
        assertThat(jdbcTemplate.queryForObject("""
                select e.egrul_status from buyer_external_data e join buyer b on b.id = e.buyer_id
                where b.inn = '5507213870'""", String.class)).startsWith("Регистрирующим органом принято решение о предстоящем исключении");
        assertThat(Api.get("/runs/" + run.get("id").asLong() + "/log").body().toString()).contains("NO_BUYER_DATA");
    }

    @Test
    void secondRunDoesNotDuplicateDocuments() throws InterruptedException {
        Fixture.run(Map.of());
        final JsonNode second = Fixture.run(Map.of());

        assertThat(Fixture.metrics(second, 1).get("newUpds").asInt()).isZero();
        assertThat(count("upd")).isEqualTo(37);
        assertThat(count("payment")).isEqualTo(13);
    }

    @Test
    void loadsOnlyDocumentsUpToCalcDateAndSelectedClients() throws InterruptedException {
        final JsonNode run = Fixture.run(Map.of("calcDate", "2026-09-30", "clientInns", java.util.List.of("7710000001")));

        assertThat(Fixture.metrics(run, 1).get("clients").asInt()).isEqualTo(1);
        // у C1 до 30.09: U-1001, U-1002, U-1003, U-1101, U-1102, U-1103
        assertThat(Fixture.metrics(run, 1).get("upds").asInt()).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("select max(upd_date) from upd", java.time.LocalDate.class))
                .isBeforeOrEqualTo(java.time.LocalDate.parse("2026-09-30"));
    }

    private int count(final String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
