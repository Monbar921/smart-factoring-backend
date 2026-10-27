package ru.smart.factoring.backend.load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.api.manualupload.ManualUploadResultDto;
import ru.smart.factoring.backend.api.manualupload.ManualUploadTableStatsDto;
import ru.smart.factoring.backend.factoring.error.ApiException;
import ru.smart.factoring.backend.factoring.source.ManualUploadService;
import ru.smart.factoring.backend.support.Api;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Партии ML-команды (контракт данных v1.0, Р-54, Р-55): manual-upload/delivery-index-v1 - index.json и две партии.
 * batch_01 - 5 клиентов из batch_01, у их покупателей есть покупатель без данных, банкротство, ликвидация, исключение
 * и «мало данных»; batch_02 - 2 клиента из batch_02.
 */
class DeliveryBatchImportTest extends AbstractSmartFactoringBackendTest {
    private static final String PATH = "delivery-index-v1/batch_01";
    private static final Path FIXTURE = Path.of("src/test/resources/manual-upload", PATH);
    private static final Path INDEX_SET = Path.of("src/test/resources/manual-upload/delivery-index-v1");
    private static final String NO_DATA_BUYER = "7327050690";
    private static final String BANKRUPT_BUYER = "2624000870";
    private static final String LIQUIDATED_BUYER = "7722478720";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ManualUploadService importService;

    @TempDir
    private Path dir;

    @BeforeEach
    void setUp() {
        TestDatabase.clean(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void importsBatchIntoGeneratorTables() {
        final Api.Response response = Api.post("/manual-upload", Map.of("path", PATH));

        assertThat(response.status()).isEqualTo(200);
        final JsonNode tables = response.body().get("tables");
        assertThat(tables.get("clients").get("inserted").asInt()).isEqualTo(5);
        assertThat(tables.get("buyers").get("inserted").asInt()).isEqualTo(18);
        assertThat(tables.get("contracts").get("inserted").asInt()).isEqualTo(19);
        assertThat(tables.get("upds").get("inserted").asInt()).isEqualTo(1093);
        assertThat(tables.get("payments").get("inserted").asInt()).isEqualTo(1150);
        assertThat(tables.get("egrulStatuses").get("inserted").asInt()).isEqualTo(18);
        assertThat(response.body().get("dataDate").asString()).isEqualTo("2025-12-30");
        assertThat(response.body().get("snapshotDate").asString()).isEqualTo("2025-12-30");

        assertThat(jdbcTemplate.queryForObject("select kind || ' ' || format_version || ' ' || source_name from manual_upload_batch",
                String.class)).isEqualTo("DELIVERY 1.0 batch_01 gen-1.0");
        // связи по client_id и contract_id - в ИНН клиента и номер договора
        assertThat(jdbcTemplate.queryForList("""
                select c.inn || ' ' || c.industry || ' ' || c.is_core || ' ' || c.manager_code || ' ' || u.contract_number
                from manual_upload_upd u join manual_upload_client c on c.inn = u.client_inn
                where u.number = 'УПД-0001' and u.upd_date = '2023-03-31' and u.buyer_inn = '5047252186'""", String.class))
                .containsExactly("0024194796 CONSTRUCTION true MG-003 ДП-002/2023");
        assertThat(jdbcTemplate.queryForList("select code from manual_upload_manager order by code", String.class))
                .containsExactly("MG-002", "MG-003", "MG-005", "MG-009");
        assertThat(count("manual_upload_buyer where inn = '" + NO_DATA_BUYER + "'")).isZero();
        assertThat(jdbcTemplate.queryForObject("select stage from manual_upload_bankruptcy_case where inn = ?", String.class, BANKRUPT_BUYER))
                .isEqualTo("Наблюдение");
        assertThat(jdbcTemplate.queryForObject("select status from manual_upload_egrul_status where inn = ?", String.class, LIQUIDATED_BUYER))
                .isEqualTo("ликвидация");
        // готовый PD - у покупателей с отчётностью, у «мало данных» его нет
        assertThat(count("manual_upload_buyer where pd is not null and pd_model_version = 'pd-1.0'")).isEqualTo(16);
    }

    @Test
    void sameBatchAgainChangesNothing() {
        importService.importGenerator(FIXTURE);

        final ManualUploadResultDto again = importService.importGenerator(FIXTURE);

        assertThat(again.tables().get("upds")).isEqualTo(new ManualUploadTableStatsDto(1093, 0, 0, 1093, 0));
        assertThat(again.tables().get("payments")).isEqualTo(new ManualUploadTableStatsDto(1150, 0, 0, 1150, 0));
        assertThat(again.issueCounts()).isEmpty();
        assertThat(count("manual_upload_upd")).isEqualTo(1093);
    }

    @Test
    void runUsesBatchStatusesAndPdOfSameModelVersion() throws IOException, InterruptedException {
        // версия модели партии - как у встроенной заглушки: PD берётся из кэша, а не считается
        copyFixture();
        final Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"pd-1.0\"", "\"pd-stub-1\""));
        importService.importGenerator(dir);

        final JsonNode run = Fixture.run(Map.of());

        assertThat(run.get("status").asString()).startsWith("DONE");
        assertThat(jdbcTemplate.queryForObject("select count(*) from run_log where event_code = 'NO_BUYER_DATA' and message like ?",
                Integer.class, "%" + NO_DATA_BUYER + "%")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                select b.inn || ' ' || s.stop_factor from buyer_score s join buyer b on b.id = s.buyer_id
                where s.stop_factor is not null order by b.inn""", String.class))
                .contains(BANKRUPT_BUYER + " BANKRUPTCY");
        final Map<String, Object> scored = jdbcTemplate.queryForMap("""
                select s.pd, s.from_cache, db.pd as batch_pd from buyer_score s
                join buyer b on b.id = s.buyer_id join manual_upload_buyer db on db.inn = b.inn
                where s.stop_factor is null and db.pd is not null
                order by b.inn limit 1""");
        assertThat(scored.get("from_cache")).isEqualTo(true);
        assertThat((BigDecimal) scored.get("pd")).isEqualByComparingTo((BigDecimal) scored.get("batch_pd"));
    }

    @Test
    void rejectsChangedFile() throws IOException {
        copyFixture();
        final Path upds = dir.resolve("upd.csv");
        Files.writeString(upds, Files.readString(upds, StandardCharsets.UTF_8).replace("1452306.28", "1452306.29"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> importService.importGenerator(dir))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails().get("errors").toString())
                        .contains("upd.csv: SHA-256 не совпадает с manifest.json"));
        assertThat(count("manual_upload_upd")).isZero();
    }

    @Test
    void importsAllBatchesOfIndexInOneArchive() throws IOException {
        // как архив коллеги: папка delivery/ с index.json и папками партий
        final ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(zip); Stream<Path> files = Files.walk(INDEX_SET)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new ZipEntry("delivery/" + INDEX_SET.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }

        final ManualUploadResultDto result = importService.importGeneratorZip(new ByteArrayInputStream(zip.toByteArray()));

        assertThat(result.tables().get("clients")).isEqualTo(new ManualUploadTableStatsDto(7, 7, 0, 0, 0));
        assertThat(result.tables().get("upds")).isEqualTo(new ManualUploadTableStatsDto(1093 + 381, 1093 + 381, 0, 0, 0));
        assertThat(result.rowCounts().get("payments")).isEqualTo(1150 + 391);
        assertThat(result.issueCounts()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select kind || ' ' || source_name from manual_upload_batch", String.class))
                .isEqualTo("DELIVERY index.json: batch_01, batch_02");

        // тот же набор ещё раз - всё «без изменений»
        final ManualUploadResultDto again = importService.importGenerator(INDEX_SET);
        assertThat(again.tables().get("upds")).isEqualTo(new ManualUploadTableStatsDto(1474, 0, 0, 1474, 0));
    }

    @Test
    void indexMustDescribeBatchesExactly() throws IOException {
        copyIndexSet();
        Files.writeString(dir.resolve("index.json"), Files.readString(dir.resolve("index.json"))
                .replace("\"batch_id\": \"batch_02\"", "\"batch_id\": \"batch_07\""));
        copy(FIXTURE, dir.resolve("batch_03"));

        assertThatThrownBy(() -> importService.importGenerator(dir))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails().get("errors").toString())
                        .contains("batch_02/manifest.json: batch_id batch_02, в index.json - batch_07")
                        .contains("index.json: папки batch_03 нет в списке партий"));
        assertThat(count("manual_upload_upd")).isZero();
    }

    @Test
    void errorInOneBatchRejectsWholeSet() throws IOException {
        copyIndexSet();
        final Path upds = dir.resolve("batch_02/upd.csv");
        Files.writeString(upds, Files.readString(upds, StandardCharsets.UTF_8) + "x", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> importService.importGenerator(dir))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getDetails().get("errors").toString())
                        .contains("batch_02/upd.csv: SHA-256 не совпадает с manifest.json"));
        assertThat(count("manual_upload_upd")).isZero();
    }

    private void copyFixture() throws IOException {
        copy(FIXTURE, dir);
    }

    private void copyIndexSet() throws IOException {
        copy(INDEX_SET, dir);
    }

    private static void copy(final Path from, final Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (final Path file : files.toList()) {
                final Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
    }

    private int count(final String tableAndWhere) {
        return jdbcTemplate.queryForObject("select count(*) from " + tableAndWhere, Integer.class);
    }
}
