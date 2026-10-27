package ru.smart.factoring.backend.load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.api.demo.DemoImportIssueDto;
import ru.smart.factoring.backend.api.demo.DemoImportResultDto;
import ru.smart.factoring.backend.api.demo.DemoTableStatsDto;
import ru.smart.factoring.backend.factoring.source.DemoImportService;
import ru.smart.factoring.backend.support.Api;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Импорт - дельта (Р-52): повторная выгрузка ничего не дублирует, новое добавляется, изменённое обновляется,
 * конфликты пропускаются, возможные дубли загружаются с предупреждением, история прогонов и решения сохраняются.
 */
class DeltaImportTest extends AbstractSmartFactoringBackendTest {
    private static final String UPDS_HEADER = "client_inn;buyer_inn;contract_number;number;upd_date;amount;control_case";
    private static final String PAYMENTS_HEADER = "source_id;client_inn;payer_inn;amount;payment_date;purpose;control_case";
    private static final String C1 = "7710000001";
    private static final String B1 = "0277120989";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DemoImportService importService;

    @TempDir
    private Path dir;

    @BeforeEach
    void setUp() {
        Fixture.importFixture(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void sameExportAgainChangesNothingAndKeepsRunsAndDecisions() throws InterruptedException {
        Fixture.run(Map.of());
        final long offer = offerId("U-1004");
        assertThat(Api.post("/offers/" + offer + "/transfer", null).status()).isEqualTo(200);

        final Api.Response again = Api.post("/demo/import", Map.of("path", Fixture.PATH));

        assertThat(again.status()).isEqualTo(200);
        final JsonNode upds = again.body().get("tables").get("upds");
        assertThat(upds.get("rows").asInt()).isEqualTo(39);
        assertThat(upds.get("unchanged").asInt()).isEqualTo(39);
        assertThat(upds.get("inserted").asInt() + upds.get("updated").asInt() + upds.get("conflicts").asInt()).isZero();
        assertThat(again.body().get("tables").get("efrsbEvents").get("unchanged").asInt()).isEqualTo(92);
        assertThat(again.body().get("issueCounts").isEmpty()).isTrue();
        assertThat(count("demo_upd")).isEqualTo(39);
        // ничего не сброшено: прогон и решение менеджера на месте
        assertThat(count("run")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from offer where id = ?", String.class, offer)).isEqualTo("TRANSFERRED");
    }

    @Test
    void deltaAddsNewAndUpdatesChanged() throws IOException, InterruptedException {
        Fixture.run(Map.of());
        export("2026-10-30",
                UPDS_HEADER,
                C1 + ";" + B1 + ";Д-101;U-1999;2026-10-30;1500000.00;",
                C1 + ";" + B1 + ";Д-101;U-1003;2026-09-15;6500000.00;K-7");

        final DemoImportResultDto result = importService.importGenerator(dir);

        assertThat(result.tables().get("upds")).isEqualTo(new DemoTableStatsDto(2, 1, 1, 0, 0));
        assertThat(result.dataDate()).hasToString("2026-10-30");
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.level()).isEqualTo("CHANGED");
            assertThat(issue.key()).isEqualTo("U-1003 / 2026-09-15 / " + C1);
            assertThat(issue.message()).contains("amount 6000000.00 → 6500000.00");
        });
        // дата расчёта по умолчанию - последняя дата всех загруженных данных; следующий прогон видит новое и изменённое
        assertThat(Api.get("/runs/defaults").body().get("dataDate").asString()).isEqualTo("2026-10-30");
        final JsonNode run = Fixture.run(Map.of());
        assertThat(Fixture.metrics(run, 1).get("newUpds").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select amount from upd where number = 'U-1003'", BigDecimal.class))
                .isEqualByComparingTo("6500000");
        assertThat(count("run")).isEqualTo(2);
    }

    @Test
    void conflictingRowsAreSkippedAndTheRestIsLoaded() throws IOException {
        export("2026-10-24",
                PAYMENTS_HEADER,
                // тот же source_id, другая сумма: строка выписки не меняется - конфликт
                "P-0001;" + C1 + ";" + B1 + ";3100000.00;2026-07-30;Оплата по УПД № U-1001 от 01.06.2026;",
                "P-0100;" + C1 + ";" + B1 + ";100000.00;2026-10-24;Оплата по договору № Д-101;");
        Files.writeString(dir.resolve("contracts.csv"), String.join("\n",
                "client_inn;buyer_inn;number;contract_date;deferral_days",
                // у договора сменился покупатель - конфликт, и УПД по нему пропускается
                C1 + ";7720000002;Д-101;2025-12-01;60"), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("upds.csv"), String.join("\n", UPDS_HEADER,
                C1 + ";7720000002;Д-101;U-1998;2026-10-24;900000.00;"), StandardCharsets.UTF_8);

        final DemoImportResultDto result = importService.importGenerator(dir);

        assertThat(result.tables().get("payments")).isEqualTo(new DemoTableStatsDto(2, 1, 0, 0, 1));
        assertThat(result.tables().get("contracts")).isEqualTo(new DemoTableStatsDto(1, 0, 0, 0, 1));
        assertThat(result.tables().get("upds")).isEqualTo(new DemoTableStatsDto(1, 0, 0, 0, 1));
        assertThat(result.issueCounts()).containsEntry("CONFLICT", 3);
        assertThat(messages(result, "CONFLICT")).anySatisfy(message -> assertThat(message).contains("amount 3000000.00 → 3100000.00"))
                .anySatisfy(message -> assertThat(message).contains("buyer_inn " + B1 + " → 7720000002"))
                .anySatisfy(message -> assertThat(message).contains("договор Д-101 уже загружен с другим покупателем"));
        // прежние данные не тронуты
        assertThat(jdbcTemplate.queryForObject("select amount from demo_payment where source_id = 'P-0001'", BigDecimal.class))
                .isEqualByComparingTo("3000000");
        assertThat(jdbcTemplate.queryForObject("select buyer_inn from demo_contract where number = 'Д-101'", String.class)).isEqualTo(B1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from demo_upd where number = 'U-1998'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from demo_payment where source_id = 'P-0100'", Integer.class)).isEqualTo(1);
    }

    @Test
    void possibleDuplicateIsLoadedWithWarning() throws IOException {
        export("2026-07-30",
                PAYMENTS_HEADER,
                "P-0001-COPY;" + C1 + ";" + B1 + ";3000000.00;2026-07-30;Оплата по УПД № U-1001 от 01.06.2026;");

        final DemoImportResultDto result = importService.importGenerator(dir);

        assertThat(result.tables().get("payments").inserted()).isEqualTo(1);
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.level()).isEqualTo("DUPLICATE");
            assertThat(issue.key()).isEqualTo("P-0001-COPY");
            assertThat(issue.message()).contains("возможный дубль P-0001");
        });
    }

    @Test
    void changeOfTransferredUpdIsWarned() throws IOException, InterruptedException {
        Fixture.run(Map.of());
        assertThat(Api.post("/offers/" + offerId("U-1004") + "/transfer", null).status()).isEqualTo(200);
        export("2026-10-01", UPDS_HEADER, C1 + ";" + B1 + ";Д-101;U-1004;2026-10-01;4800000.00;K-1");

        final DemoImportResultDto result = importService.importGenerator(dir);

        assertThat(messages(result, "WARNING")).singleElement().asString()
                .contains("УПД уже передан в факторинг, сумма изменилась: 5000000.00 → 4800000.00");
        assertThat(result.tables().get("upds").updated()).isEqualTo(1);
    }

    @Test
    void resetClearsDataRunsAndDecisions() throws InterruptedException {
        Fixture.run(Map.of());

        assertThat(Api.delete("/demo/data").status()).isEqualTo(204);

        for (final String table : List.of("demo_upd", "demo_payment", "demo_efrsb_event", "demo_import_batch", "upd", "run", "offer")) {
            assertThat(count(table)).as(table).isZero();
        }
        assertThat(count("parameter")).isPositive();
        assertThat(Api.get("/runs/defaults").body().get("dataDate").isNull()).isTrue();
    }

    private void export(final String dataDate, final String header, final String... rows) throws IOException {
        Files.writeString(dir.resolve("manifest.json"),
                "{\"format_version\": \"1\", \"generator\": \"delta test\", \"data_date\": \"" + dataDate + "\"}", StandardCharsets.UTF_8);
        final String file = header.startsWith("source_id") ? "payments.csv" : "upds.csv";
        Files.writeString(dir.resolve(file), header + "\n" + String.join("\n", rows), StandardCharsets.UTF_8);
    }

    private long offerId(final String number) {
        return jdbcTemplate.queryForObject("""
                select o.id from offer o join upd u on u.id = o.upd_id where u.number = ? and o.status = 'QUEUED'""", Long.class, number);
    }

    private static List<String> messages(final DemoImportResultDto result, final String level) {
        return result.issues().stream().filter(issue -> level.equals(issue.level())).map(DemoImportIssueDto::message).toList();
    }

    private int count(final String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
