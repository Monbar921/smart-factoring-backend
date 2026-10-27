package ru.smart.factoring.backend.factoring.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.smart.factoring.backend.api.manualupload.ManualUploadResultDto;
import ru.smart.factoring.backend.api.manualupload.ManualUploadTableStatsDto;
import ru.smart.factoring.backend.factoring.error.ApiException;
import ru.smart.factoring.backend.factoring.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Импорт данных ручной загрузки в таблицы manual_upload_* (ДД-1): выгрузка генератора и events.csv
 * (формат - docs/claude/module-0/manual-upload-format.md), партия ML-команды - {@link DeliveryBatch}.
 * <p>
 * Импорт - дельта (Р-52): новые записи добавляются, существующие (по естественному ключу) обновляются, ничего
 * не удаляется; история прогонов и решения менеджеров сохраняются. Строка, у которой при том же ключе изменилось
 * неизменяемое поле, пропускается (конфликт), остальное загружается; возможные дубли загружаются с предупреждением.
 * Всё - в отчёте импорта. Ошибки формата и ссылок по-прежнему отклоняют выгрузку целиком.
 * Полная очистка данных ручной загрузки - отдельное действие {@link #reset()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualUploadService {
    static final String FORMAT_VERSION = "1";
    private static final String MANIFEST = "manifest.json";
    private static final String INDEX = "index.json";
    private static final String DELIVERY = "DELIVERY";
    private static final String EVENTS = "events.csv";
    private static final String CONTROL_CASE = "control_case";
    private static final Set<String> INDUSTRIES = Set.of("CONSTRUCTION", "PROFESSIONAL", "MANUFACTURING", "TRADE");
    private static final List<String> FINANCIAL_COLUMNS = List.of(
            "assets_total", "current_assets", "receivables", "cash", "equity", "long_term_borrowings", "short_term_liabilities",
            "short_term_borrowings", "payables", "revenue", "cost_of_sales", "sales_profit", "interest_payable", "net_profit");
    /**
     * Загруженные данные и результаты прогонов: удаляются только полной очисткой.
     */
    private static final List<String> LOADED_AND_RESULTS = List.of(
            "run_log", "notification", "offer", "upd_decision", "upd_score", "buyer_score", "buyer_pd_cache", "upd_state",
            "payment_allocation", "run_step", "payment", "upd", "contract", "buyer_financials", "buyer_external_data",
            "client", "buyer", "manager", "run", "imitation_factoring_package", "imitation_risk_signal");
    private static final List<String> SOURCES = List.of(
            "manual_upload_manager", "manual_upload_client", "manual_upload_buyer", "manual_upload_buyer_financials",
            "manual_upload_contract", "manual_upload_upd", "manual_upload_payment", "manual_upload_egrul_status",
            "manual_upload_bankruptcy_case", "manual_upload_efrsb_event", "manual_upload_batch");

    private static final SourceUpsert MANAGERS = new SourceUpsert("managers", "manual_upload_manager", "managers.csv", List.of("code"), Set.of(),
            List.of(), List.of("full_name"), false, List.of());
    private static final SourceUpsert CLIENTS = new SourceUpsert("clients", "manual_upload_client", "clients.csv", List.of("inn"), Set.of(),
            List.of(), List.of("name", "industry", "okved_section", "region", "abs_liquidity", "ar_turnover_days", "is_core",
            "manager_code", "edo_consent", "factoring_client", CONTROL_CASE), false, List.of());
    private static final SourceUpsert BUYERS = new SourceUpsert("buyers", "manual_upload_buyer", "buyers.csv", List.of("inn"), Set.of(),
            List.of(), List.of("ogrn", "name", "okved", "okved_section", "region", "registration_date", "data_date", "pd",
            "pd_model_version"), false, List.of());
    private static final SourceUpsert FINANCIALS = new SourceUpsert("buyerFinancials", "manual_upload_buyer_financials", "buyer_financials.csv",
            List.of("inn", "year"), Set.of(), List.of(),
            Stream.of(List.of("simplified"), FINANCIAL_COLUMNS, List.of("data_date")).flatMap(List::stream).toList(), false, List.of());
    /** у договора нельзя сменить покупателя; дата и отсрочка - можно (допсоглашение) */
    private static final SourceUpsert CONTRACTS = new SourceUpsert("contracts", "manual_upload_contract", "contracts.csv",
            List.of("client_inn", "number"), Set.of(), List.of("buyer_inn"), List.of("contract_date", "deferral_days"), true, List.of());
    /** у УПД нельзя сменить покупателя и договор; сумма может измениться (корректировочный документ) */
    private static final SourceUpsert UPDS = new SourceUpsert("upds", "manual_upload_upd", "upds.csv", List.of("number", "upd_date", "client_inn"),
            Set.of(), List.of("buyer_inn", "contract_number"), List.of("amount", CONTROL_CASE), true,
            List.of("client_inn", "buyer_inn", "upd_date", "amount"));
    /** строка выписки неизменна: сумма, дата, получатель и плательщик; назначение - уточняется */
    private static final SourceUpsert PAYMENTS = new SourceUpsert("payments", "manual_upload_payment", "payments.csv", List.of("source_id"),
            Set.of(), List.of("client_inn", "payer_inn", "amount", "payment_date"), List.of("purpose", CONTROL_CASE), true,
            List.of("client_inn", "payer_inn", "amount", "payment_date", "purpose"));
    private static final SourceUpsert EGRUL = new SourceUpsert("egrulStatuses", "manual_upload_egrul_status", EVENTS, List.of("inn"), Set.of(),
            List.of(), List.of("ogrn", "name", "region", "debtor_category", "status", "status_date"), false, List.of());
    private static final SourceUpsert CASES = new SourceUpsert("bankruptcyCases", "manual_upload_bankruptcy_case", EVENTS,
            List.of("inn", "case_number"), Set.of(), List.of(), List.of("court", "applicant", "stage", "start_date", "start_date_source",
            "observation_from", "rehabilitation_from", "external_management_from", "declared_bankrupt_on", "receivership_from",
            "end_date", "outcome", "first_intent_date"), false, List.of());
    private static final SourceUpsert EFRSB_EVENTS = new SourceUpsert("efrsbEvents", "manual_upload_efrsb_event", EVENTS,
            List.of("inn", "case_number", "message_number", "event_type", "event"), Set.of("case_number", "event_type", "event"),
            List.of(), List.of("details", "event_date", "published_on", "annulled", "url"), false, List.of());

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ManualUploadProperties properties;
    private final Json json;

    /**
     * Путь из запроса - относительно каталога импорта; выйти за его пределы нельзя.
     */
    public Path resolve(final String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw ApiException.validation("Не задан путь");
        }
        final Path base = properties.importDir().toAbsolutePath().normalize();
        final Path path = base.resolve(relativePath).normalize();
        if (!path.startsWith(base) || !Files.exists(path)) {
            throw ApiException.validation("Нет такого пути в каталоге импорта: " + relativePath);
        }
        return path;
    }

    /**
     * Выгрузка генератора или партия ML-команды (Р-54) - формат определяется по manifest.json;
     * набор партий с index.json - {@link #importIndex(Path)}.
     */
    public ManualUploadResultDto importGenerator(final Path dir) {
        final Path index = findIndex(dir);
        if (index != null) {
            return importIndex(index);
        }
        final ImportValidator v = new ImportValidator();
        final ImportReport report = new ImportReport();
        final Prepared prepared = prepare(findRoot(dir), v, report, existing());
        if (v.hasErrors()) {
            throw invalid(v);
        }
        return transactionTemplate.execute(status -> {
            ensureNoRunningRun();
            return finish(prepared.kind(), prepared.formatVersion(), prepared.seed(), prepared.snapshotDate(), prepared.sourceName(),
                    writeAll(prepared, report), report);
        });
    }

    /**
     * Набор партий ML-команды (Р-55): index.json со списком партий (batch_id, path) и папки партий рядом с ним.
     * Загружаются все партии из списка и только они, одной транзакцией: ошибка в любой партии отклоняет весь набор.
     * Отчёт - общий, файлы в замечаниях - с папкой партии (batch_01/upd.csv).
     */
    private ManualUploadResultDto importIndex(final Path indexFile) {
        final Path base = indexFile.getParent();
        final ImportValidator v = new ImportValidator();
        final Map<String, Object> index = readJson(indexFile, INDEX, v);
        if (!index.isEmpty() && !DeliveryBatch.CONTRACT_VERSION.equals(stringValue(index.get("contract_version")))) {
            v.error(INDEX, null, "contract_version должен быть " + DeliveryBatch.CONTRACT_VERSION);
        }
        final List<?> entries = index.get("batches") instanceof List<?> list ? list : List.of();
        if (!index.isEmpty() && entries.isEmpty()) {
            v.error(INDEX, null, "нет списка партий batches");
        }
        final Existing existing = existing();
        final List<Part> parts = new ArrayList<>();
        final Set<String> ids = new LinkedHashSet<>();
        final Set<Path> listed = new HashSet<>();
        for (final Object entry : entries) {
            final Map<?, ?> batch = entry instanceof Map<?, ?> map ? map : Map.of();
            final String id = stringValue(batch.get("batch_id"));
            final String path = stringValue(batch.get("path"));
            if (id == null || path == null) {
                v.error(INDEX, null, "у партии нет batch_id или path: " + entry);
                continue;
            }
            final Path root = base.resolve(path).normalize();
            if (!root.startsWith(base) || !Files.isRegularFile(root.resolve(MANIFEST))) {
                v.error(INDEX, null, "партия " + id + ": нет папки " + path + " с manifest.json");
                continue;
            }
            if (!ids.add(id) || !listed.add(root)) {
                v.error(INDEX, null, "партия " + id + " (" + path + ") указана дважды");
                continue;
            }
            final String folder = base.relativize(root).toString().replace('\\', '/') + "/";
            final ImportReport report = new ImportReport();
            v.prefix(folder);
            final Prepared prepared = prepare(root, v, report, existing);
            if (!prepared.kind().equals(DELIVERY)) {
                v.error(MANIFEST, null, "не партия ML-команды: нет contract_version");
            } else {
                if (!id.equals(stringValue(prepared.source().get("batch_id")))) {
                    v.error(MANIFEST, null, "batch_id " + prepared.source().get("batch_id") + ", в index.json - " + id);
                }
                if (index.get("D_end") != null && !Objects.equals(stringValue(index.get("D_end")), stringValue(prepared.source().get("D_end")))) {
                    v.error(MANIFEST, null, "D_end " + prepared.source().get("D_end") + ", в index.json - " + index.get("D_end"));
                }
            }
            v.prefix("");
            parts.add(new Part(folder, prepared, report));
        }
        unlistedBatches(base, listed, v);
        if (v.hasErrors()) {
            throw invalid(v);
        }
        return transactionTemplate.execute(status -> {
            ensureNoRunningRun();
            final Map<String, Integer> counts = new LinkedHashMap<>();
            final ImportReport total = new ImportReport();
            LocalDate snapshotDate = null;
            for (final Part part : parts) {
                writeAll(part.prepared(), part.report()).forEach((table, rows) -> counts.merge(table, rows, Integer::sum));
                total.add(part.report(), part.folder());
                snapshotDate = max(snapshotDate, part.prepared().snapshotDate());
            }
            return finish(DELIVERY, stringValue(index.get("contract_version")), null, snapshotDate,
                    "index.json: " + String.join(", ", ids), counts, total);
        });
    }

    /**
     * Папка с manifest.json рядом с index.json, но не из его списка - набор не совпадает с описанием.
     */
    private static void unlistedBatches(final Path base, final Set<Path> listed, final ImportValidator v) {
        try (Stream<Path> dirs = Files.list(base)) {
            dirs.filter(path -> Files.isRegularFile(path.resolve(MANIFEST)) && !listed.contains(path.normalize()))
                    .sorted()
                    .forEach(path -> v.error(INDEX, null, "папки " + path.getFileName() + " нет в списке партий"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Чтение и проверка одной выгрузки или партии - до записи, без транзакции.
     */
    private Prepared prepare(final Path root, final ImportValidator v, final ImportReport report, final Existing existing) {
        final Map<String, Object> source = readManifest(root, v);
        final boolean delivery = DeliveryBatch.matches(source);
        final Map<String, Object> manifest;
        final Tables tables;
        final EfrsbTables efrsb;
        if (delivery) {
            final DeliveryBatch.Converted batch = DeliveryBatch.read(root, source, v);
            manifest = batch.manifest();
            tables = batch.tables();
            efrsb = batch.efrsb();
        } else {
            if (!source.isEmpty() && !FORMAT_VERSION.equals(stringValue(source.get("format_version")))) {
                v.error(MANIFEST, null, "format_version должен быть " + FORMAT_VERSION
                        + " (или contract_version " + DeliveryBatch.CONTRACT_VERSION + " для партии ML-команды)");
            }
            manifest = source;
            tables = readGeneratorTables(root, v);
            efrsb = Files.exists(root.resolve(EVENTS)) ? readEfrsb(root.resolve(EVENTS), v) : null;
        }
        if (tables.isEmpty() && efrsb == null) {
            v.error(MANIFEST, null, "в выгрузке нет ни одного файла с данными");
        }
        final Set<Integer> skippedUpds = new HashSet<>();
        validateGenerator(tables, manifest, existing, v, report, skippedUpds);
        return new Prepared(delivery ? DELIVERY : "GENERATOR", source,
                stringValue(delivery ? source.get("contract_version") : manifest.get("format_version")),
                manifest.get("seed") instanceof Number seed ? seed.longValue() : null, date(manifest.get("snapshot_date")),
                stringValue(manifest.get("generator")), tables, efrsb, skippedUpds);
    }

    private Map<String, Integer> writeAll(final Prepared prepared, final ImportReport report) {
        final Map<String, Integer> counts = writeGenerator(prepared.tables(), report, prepared.skippedUpds());
        if (prepared.efrsb() != null) {
            counts.putAll(writeEfrsb(prepared.efrsb(), report));
        }
        return counts;
    }

    public ManualUploadResultDto importEfrsb(final Path file) {
        final ImportValidator v = new ImportValidator();
        final EfrsbTables efrsb = readEfrsb(file, v);
        if (v.hasErrors()) {
            throw invalid(v);
        }
        return transactionTemplate.execute(status -> {
            ensureNoRunningRun();
            final ImportReport report = new ImportReport();
            final Map<String, Integer> counts = writeEfrsb(efrsb, report);
            return finish("EFRSB", null, null, efrsb.snapshotDate(), file.getFileName().toString(), counts, report);
        });
    }

    /**
     * Полная очистка данных ручной загрузки: источники, загруженные данные, прогоны и решения менеджеров (параметры остаются).
     * Отдельное действие оператора с подтверждением - импорт сам ничего не стирает (Р-52).
     */
    public void reset() {
        transactionTemplate.executeWithoutResult(status -> {
            ensureNoRunningRun();
            jdbc.getJdbcTemplate().execute("truncate table " + String.join(", ", Stream.concat(LOADED_AND_RESULTS.stream(),
                    SOURCES.stream()).toList()) + " restart identity cascade");
            log.info("Данные ручной загрузки очищены");
        });
    }

    private ManualUploadResultDto finish(final String kind, final String formatVersion, final Long seed, final LocalDate snapshotDate,
                                       final String sourceName, final Map<String, Integer> counts, final ImportReport report) {
        final LocalDate dataDate = jdbc.queryForObject("select greatest((select max(upd_date) from manual_upload_upd), "
                + "(select max(payment_date) from manual_upload_payment))", Map.of(), LocalDate.class);
        final long batchId = Objects.requireNonNull(jdbc.queryForObject("""
                        insert into manual_upload_batch (kind, format_version, seed, data_date, snapshot_date, source_name, row_counts, report)
                        values (:kind, :formatVersion, :seed, :dataDate, :snapshotDate, :sourceName, cast(:rowCounts as jsonb),
                                cast(:report as jsonb))
                        returning id""",
                new MapSqlParameterSource()
                        .addValue("kind", kind)
                        .addValue("formatVersion", formatVersion)
                        .addValue("seed", seed)
                        .addValue("dataDate", dataDate)
                        .addValue("snapshotDate", snapshotDate)
                        .addValue("sourceName", sourceName)
                        .addValue("rowCounts", json.write(counts))
                        .addValue("report", json.write(Map.of("tables", report.tables(), "issueCounts", report.issueCounts(),
                                "issues", report.issues()))),
                Long.class));
        log.info("Импорт {} {}: {}, замечания {}", kind, batchId, report.tables(), report.issueCounts());
        return new ManualUploadResultDto(batchId, dataDate, snapshotDate, counts, report.tables(), report.issueCounts(), report.issues());
    }

    /**
     * ZIP с выгрузкой генератора: распаковывается во временный каталог и импортируется как папка.
     */
    public ManualUploadResultDto importGeneratorZip(final InputStream zip) {
        final Path dir = unzip(zip);
        try {
            return importGenerator(dir);
        } finally {
            deleteQuietly(dir);
        }
    }

    public ManualUploadResultDto importEfrsbFile(final InputStream content) {
        try {
            final Path file = Files.createTempFile("efrsb", ".csv");
            try {
                Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
                return importEfrsb(file);
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ чтение и проверка выгрузки генератора

    private Map<String, Object> readManifest(final Path root, final ImportValidator v) {
        return readJson(root.resolve(MANIFEST), MANIFEST, v);
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private Map<String, Object> readJson(final Path file, final String name, final ImportValidator v) {
        if (!Files.exists(file)) {
            v.error(name, null, "нет файла");
            return Map.of();
        }
        try {
            return json.readMap(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            v.error(name, null, "не JSON: " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * Все файлы необязательны: дельта может содержать, например, только новые УПД и платежи.
     */
    private Tables readGeneratorTables(final Path root, final ImportValidator v) {
        return new Tables(
                table(root, "managers.csv", v, List.of("code", "full_name")),
                table(root, "clients.csv", v, List.of("inn", "name", "industry", "abs_liquidity", "is_core", "manager_code",
                        "edo_consent", "factoring_client")),
                table(root, "buyers.csv", v, List.of("inn", "name")),
                table(root, "buyer_financials.csv", v, List.of("inn", "year")),
                table(root, "contracts.csv", v, List.of("client_inn", "buyer_inn", "number", "contract_date", "deferral_days")),
                table(root, "upds.csv", v, List.of("client_inn", "buyer_inn", "contract_number", "number", "upd_date", "amount")),
                table(root, "payments.csv", v, List.of("source_id", "client_inn", "payer_inn", "amount", "payment_date")));
    }

    private static Csv.Table table(final Path root, final String name, final ImportValidator v, final List<String> required) {
        final Path file = root.resolve(name);
        if (!Files.exists(file)) {
            return new Csv.Table(List.of(), List.of());
        }
        final Csv.Table table = Csv.read(file);
        return v.columns(name, table, required) ? table : new Csv.Table(List.of(), List.of());
    }

    /**
     * Уже загруженное: ссылки выгрузки-дельты проверяются по файлу и по ним.
     */
    private Existing existing() {
        final Map<String, String> contracts = new HashMap<>();
        jdbc.getJdbcTemplate().query("select client_inn, number, buyer_inn from manual_upload_contract",
                (RowCallbackHandler) rs -> contracts.put(rs.getString("client_inn") + "|" + rs.getString("number"), rs.getString("buyer_inn")));
        return new Existing(
                new HashSet<>(jdbc.getJdbcTemplate().queryForList("select code from manual_upload_manager", String.class)),
                new HashSet<>(jdbc.getJdbcTemplate().queryForList("select inn from manual_upload_client", String.class)),
                contracts);
    }

    private void validateGenerator(final Tables t, final Map<String, Object> manifest, final Existing existing, final ImportValidator v,
                                   final ImportReport report, final Set<Integer> skippedUpds) {
        final Set<String> managers = new HashSet<>();
        for (final Csv.Row row : t.managers().rows()) {
            v.required("managers.csv", row, "full_name");
            v.unique("managers.csv", row, managers, v.required("managers.csv", row, "code"));
        }
        final Set<String> clients = new HashSet<>();
        for (final Csv.Row row : t.clients().rows()) {
            final String file = "clients.csv";
            v.unique(file, row, clients, v.required(file, row, "inn"));
            v.required(file, row, "name");
            final String industry = v.required(file, row, "industry");
            if (industry != null && !INDUSTRIES.contains(industry)) {
                v.error(file, row.line(), "industry должен быть одним из " + INDUSTRIES);
            }
            v.decimal(file, row, "abs_liquidity", true);
            v.integer(file, row, "ar_turnover_days", false);
            v.bool(file, row, "is_core", true);
            v.bool(file, row, "edo_consent", true);
            v.bool(file, row, "factoring_client", true);
            final String manager = v.required(file, row, "manager_code");
            if (manager != null && !managers.contains(manager) && !existing.managers().contains(manager)) {
                v.error(file, row.line(), "нет менеджера " + manager + " ни в managers.csv, ни в загруженных");
            }
        }
        final Set<String> buyers = new HashSet<>();
        for (final Csv.Row row : t.buyers().rows()) {
            v.unique("buyers.csv", row, buyers, v.required("buyers.csv", row, "inn"));
            v.required("buyers.csv", row, "name");
            v.date("buyers.csv", row, "registration_date", false);
            v.date("buyers.csv", row, "data_date", false);
        }
        final Set<String> financials = new HashSet<>();
        for (final Csv.Row row : t.financials().rows()) {
            final String file = "buyer_financials.csv";
            v.unique(file, row, financials, v.required(file, row, "inn") + "|" + v.integer(file, row, "year", true));
            v.bool(file, row, "simplified", false);
            FINANCIAL_COLUMNS.forEach(column -> v.decimal(file, row, column, false));
            v.date(file, row, "data_date", false);
        }
        clients.addAll(existing.clients());
        // договоры пары: загруженные, поверх - из файла; договор со сменой покупателя - конфликт (строка пропустится)
        final Map<String, String> contractBuyer = new HashMap<>(existing.contracts());
        final Map<String, String> conflictingContracts = new HashMap<>();
        final Set<String> fileContracts = new HashSet<>();
        for (final Csv.Row row : t.contracts().rows()) {
            final String file = "contracts.csv";
            final String client = v.required(file, row, "client_inn");
            final String number = v.required(file, row, "number");
            final String buyer = v.required(file, row, "buyer_inn");
            final String key = client + "|" + number;
            if (!fileContracts.add(key)) {
                v.error(file, row.line(), "повтор ключа " + key);
            }
            final String loaded = existing.contracts().get(key);
            if (loaded != null && !loaded.equals(buyer)) {
                conflictingContracts.put(key, buyer);
            } else {
                contractBuyer.put(key, buyer);
            }
            if (client != null && !clients.contains(client)) {
                v.error(file, row.line(), "нет клиента " + client + " ни в clients.csv, ни в загруженных");
            }
            v.date(file, row, "contract_date", true);
            final Integer deferral = v.integer(file, row, "deferral_days", true);
            if (deferral != null && deferral < 0) {
                v.error(file, row.line(), "deferral_days < 0");
            }
        }
        LocalDate latest = null;
        final Set<String> upds = new HashSet<>();
        for (final Csv.Row row : t.upds().rows()) {
            final String file = "upds.csv";
            final String client = v.required(file, row, "client_inn");
            final LocalDate date = v.date(file, row, "upd_date", true);
            v.unique(file, row, upds, v.required(file, row, "number") + "|" + date + "|" + client);
            final String buyer = v.required(file, row, "buyer_inn");
            final String contract = v.required(file, row, "contract_number");
            if (client != null && !clients.contains(client)) {
                v.error(file, row.line(), "нет клиента " + client + " ни в clients.csv, ни в загруженных");
            }
            final String contractKey = client + "|" + contract;
            if (buyer != null && buyer.equals(conflictingContracts.get(contractKey))) {
                // договор этой строки пропущен как конфликт - пропускается и УПД по нему
                skippedUpds.add(row.line());
                report.issue(ImportReport.Level.CONFLICT, file, row.line(), row.get("number"),
                        "договор " + contract + " уже загружен с другим покупателем - УПД пропущен");
            } else if (!buyer(contractBuyer, contractKey).equals(buyer)) {
                v.error(file, row.line(), "нет договора " + contract + " пары " + client + " - " + buyer + " ни в contracts.csv, ни в загруженных");
            }
            positive(file, row, v.decimal(file, row, "amount", true), v);
            latest = max(latest, date);
        }
        final Set<String> payments = new HashSet<>();
        for (final Csv.Row row : t.payments().rows()) {
            final String file = "payments.csv";
            v.unique(file, row, payments, v.required(file, row, "source_id"));
            final String client = v.required(file, row, "client_inn");
            if (client != null && !clients.contains(client)) {
                v.error(file, row.line(), "нет клиента " + client + " ни в clients.csv, ни в загруженных");
            }
            v.required(file, row, "payer_inn");
            positive(file, row, v.decimal(file, row, "amount", true), v);
            latest = max(latest, v.date(file, row, "payment_date", true));
        }
        final LocalDate declared = date(manifest.get("data_date"));
        if (declared == null) {
            v.error(MANIFEST, null, "нет data_date");
        } else if (latest != null && !declared.equals(latest)) {
            v.error(MANIFEST, null, "data_date " + declared + " не равна последней дате УПД и платежей " + latest);
        }
        if (manifest.containsKey("snapshot_date") && date(manifest.get("snapshot_date")) == null) {
            v.error(MANIFEST, null, "snapshot_date не дата");
        }
    }

    private static String buyer(final Map<String, String> contractBuyer, final String key) {
        return Objects.toString(contractBuyer.get(key), "");
    }

    private static void positive(final String file, final Csv.Row row, final BigDecimal amount, final ImportValidator v) {
        if (amount != null && amount.signum() <= 0) {
            v.error(file, row.line(), "amount должен быть больше 0");
        }
    }

    // ------------------------------------------------------------------ events.csv

    private EfrsbTables readEfrsb(final Path file, final ImportValidator v) {
        final String name = EVENTS;
        final Csv.Table table = Csv.read(file);
        if (!v.columns(name, table, List.of("ИНН", "Статус в ЕГРЮЛ", "Дата статуса ЕГРЮЛ", "Номер дела", "Статус дела",
                "Дата окончания дела", "Тип события", "Событие", "Дата события", "Номер сообщения"))) {
            return new EfrsbTables(List.of(), List.of(), List.of(), null);
        }
        final Map<String, MapSqlParameterSource> statuses = new LinkedHashMap<>();
        final Map<String, MapSqlParameterSource> cases = new LinkedHashMap<>();
        final List<MapSqlParameterSource> events = new ArrayList<>();
        final Set<String> eventKeys = new HashSet<>();
        LocalDate snapshot = null;
        for (final Csv.Row row : table.rows()) {
            final String inn = v.required(name, row, "ИНН");
            if (inn == null) {
                continue;
            }
            final LocalDate statusDate = v.date(name, row, "Дата статуса ЕГРЮЛ", false);
            statuses.computeIfAbsent(inn, key -> new MapSqlParameterSource()
                    .addValue("inn", inn)
                    .addValue("ogrn", row.get("ОГРН"))
                    .addValue("name", row.get("Наименование"))
                    .addValue("region", row.get("Регион"))
                    .addValue("debtor_category", row.get("Категория должника"))
                    .addValue("status", row.get("Статус в ЕГРЮЛ"))
                    .addValue("status_date", statusDate));
            final String caseNumber = row.get("Номер дела");
            if (caseNumber != null) {
                cases.computeIfAbsent(inn + "|" + caseNumber, key -> new MapSqlParameterSource()
                        .addValue("inn", inn)
                        .addValue("case_number", caseNumber)
                        .addValue("court", row.get("Суд"))
                        .addValue("applicant", row.get("Заявитель"))
                        .addValue("stage", row.get("Статус дела"))
                        .addValue("start_date", v.date(name, row, "Дата начала дела", false))
                        .addValue("start_date_source", row.get("Источник даты начала"))
                        .addValue("observation_from", v.date(name, row, "Наблюдение с", false))
                        .addValue("rehabilitation_from", v.date(name, row, "Финансовое оздоровление с", false))
                        .addValue("external_management_from", v.date(name, row, "Внешнее управление с", false))
                        .addValue("declared_bankrupt_on", v.date(name, row, "Признан банкротом", false))
                        .addValue("receivership_from", v.date(name, row, "Конкурсное производство с", false))
                        .addValue("end_date", v.date(name, row, "Дата окончания дела", false))
                        .addValue("outcome", row.get("Итог дела"))
                        .addValue("first_intent_date", v.date(name, row, "Первое намерение/заявление о банкротстве", false)));
            }
            final String message = v.required(name, row, "Номер сообщения");
            v.unique(name, row, eventKeys, String.join("|", inn, Objects.toString(caseNumber, ""), Objects.toString(message, ""),
                    Objects.toString(row.get("Тип события"), ""), Objects.toString(row.get("Событие"), "")));
            final LocalDate eventDate = v.date(name, row, "Дата события", false);
            final LocalDate publishedOn = v.date(name, row, "Дата публикации", false);
            events.add(new MapSqlParameterSource()
                    .addValue("inn", inn)
                    .addValue("case_number", caseNumber)
                    .addValue("event_type", row.get("Тип события"))
                    .addValue("event", row.get("Событие"))
                    .addValue("details", row.get("Детали"))
                    .addValue("event_date", eventDate)
                    .addValue("published_on", publishedOn)
                    .addValue("message_number", message)
                    .addValue("annulled", "да".equalsIgnoreCase(Objects.toString(row.get("Аннулировано"), "").trim()))
                    .addValue("url", row.get("Ссылка")));
            snapshot = max(max(max(snapshot, statusDate), eventDate), publishedOn);
        }
        return new EfrsbTables(new ArrayList<>(statuses.values()), new ArrayList<>(cases.values()), events, snapshot);
    }

    // ------------------------------------------------------------------ запись

    private Map<String, Integer> writeGenerator(final Tables t, final ImportReport report, final Set<Integer> skippedUpds) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        write(MANAGERS, t.managers(), Set.of(), counts, report, (row, p) -> p.addValue("code", row.get("code"))
                .addValue("full_name", row.get("full_name")));
        write(CLIENTS, t.clients(), Set.of(), counts, report, (row, p) -> p.addValue("inn", row.get("inn")).addValue("name", row.get("name"))
                .addValue("industry", row.get("industry")).addValue("okved_section", row.get("okved_section"))
                .addValue("region", row.get("region")).addValue("abs_liquidity", decimal(row.get("abs_liquidity")))
                .addValue("ar_turnover_days", integer(row.get("ar_turnover_days")))
                .addValue("is_core", bool(row.get("is_core"))).addValue("manager_code", row.get("manager_code"))
                .addValue("edo_consent", bool(row.get("edo_consent")))
                .addValue("factoring_client", bool(row.get("factoring_client")))
                .addValue(CONTROL_CASE, row.get(CONTROL_CASE)));
        write(BUYERS, t.buyers(), Set.of(), counts, report, (row, p) -> p.addValue("inn", row.get("inn")).addValue("ogrn", row.get("ogrn"))
                .addValue("name", row.get("name")).addValue("okved", row.get("okved"))
                .addValue("okved_section", row.get("okved_section")).addValue("region", row.get("region"))
                .addValue("registration_date", date(row.get("registration_date")))
                .addValue("data_date", date(row.get("data_date")))
                .addValue("pd", decimal(row.get("pd"))).addValue("pd_model_version", row.get("pd_model_version")));
        write(FINANCIALS, t.financials(), Set.of(), counts, report, (row, p) -> {
            p.addValue("inn", row.get("inn")).addValue("year", integer(row.get("year")))
                    .addValue("simplified", Boolean.TRUE.equals(bool(row.get("simplified"))))
                    .addValue("data_date", date(row.get("data_date")));
            FINANCIAL_COLUMNS.forEach(column -> p.addValue(column, decimal(row.get(column))));
        });
        write(CONTRACTS, t.contracts(), Set.of(), counts, report, (row, p) -> p.addValue("client_inn", row.get("client_inn"))
                .addValue("buyer_inn", row.get("buyer_inn")).addValue("number", row.get("number"))
                .addValue("contract_date", date(row.get("contract_date"))).addValue("deferral_days", integer(row.get("deferral_days"))));
        final ManualUploadTableStatsDto upds = write(UPDS, t.upds(), skippedUpds, counts, report, (row, p) -> p
                .addValue("client_inn", row.get("client_inn")).addValue("buyer_inn", row.get("buyer_inn"))
                .addValue("contract_number", row.get("contract_number"))
                .addValue("number", row.get("number")).addValue("upd_date", date(row.get("upd_date")))
                .addValue("amount", decimal(row.get("amount"))).addValue(CONTROL_CASE, row.get(CONTROL_CASE)));
        if (upds != null) {
            warnTransferredChanged(report);
        }
        write(PAYMENTS, t.payments(), Set.of(), counts, report, (row, p) -> p.addValue("source_id", row.get("source_id"))
                .addValue("client_inn", row.get("client_inn")).addValue("payer_inn", row.get("payer_inn"))
                .addValue("amount", decimal(row.get("amount"))).addValue("payment_date", date(row.get("payment_date")))
                .addValue("purpose", row.get("purpose")).addValue(CONTROL_CASE, row.get(CONTROL_CASE)));
        return counts;
    }

    /**
     * @return итог по таблице; null - строк для записи не осталось (временной таблицы нет)
     */
    private ManualUploadTableStatsDto write(final SourceUpsert target, final Csv.Table table, final Set<Integer> skipped,
                                    final Map<String, Integer> counts, final ImportReport report, final RowBinder binder) {
        final List<SourceUpsert.Row> rows = table.rows().stream()
                .filter(row -> !skipped.contains(row.line()))
                .map(row -> {
                    final MapSqlParameterSource params = new MapSqlParameterSource();
                    binder.bind(row, params);
                    return new SourceUpsert.Row(row.line(), params);
                })
                .toList();
        counts.put(target.name(), table.rows().size());
        final ManualUploadTableStatsDto stats = target.write(jdbc, rows, report);
        final int skippedRows = table.rows().size() - rows.size();
        if (skippedRows > 0) {
            // строки, пропущенные до записи (УПД по договору в конфликте), - тоже конфликты таблицы
            report.table(target.name(), stats == null
                    ? new ManualUploadTableStatsDto(table.rows().size(), 0, 0, 0, skippedRows)
                    : new ManualUploadTableStatsDto(table.rows().size(), stats.inserted(), stats.updated(), stats.unchanged(),
                    stats.conflicts() + skippedRows));
        }
        return stats;
    }

    /**
     * УПД, уже переданный в факторинг, пришёл с другой суммой: загружается, но менеджеру и оператору это нужно видеть.
     * Основная таблица upd ещё со старой суммой - обновится шагом 1 следующего прогона.
     */
    private void warnTransferredChanged(final ImportReport report) {
        jdbc.getJdbcTemplate().query("""
                        select s.line, s.number, u.amount as before, s.amount as after
                        from stg_manual_upload_upd s
                        join client c on c.inn = s.client_inn
                        join upd u on u.client_id = c.id and u.number = s.number and u.upd_date = s.upd_date
                        join offer o on o.upd_id = u.id and o.status = 'TRANSFERRED'
                        where u.amount <> s.amount
                        order by s.line""",
                (RowCallbackHandler) rs -> report.issue(ImportReport.Level.WARNING, "upds.csv",
                        rs.getInt("line"), rs.getString("number"), "УПД уже передан в факторинг, сумма изменилась: "
                                + rs.getBigDecimal("before") + " → " + rs.getBigDecimal("after")));
    }

    private Map<String, Integer> writeEfrsb(final EfrsbTables efrsb, final ImportReport report) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(EGRUL.name(), efrsb.statuses().size());
        EGRUL.write(jdbc, rows(efrsb.statuses()), report);
        counts.put(CASES.name(), efrsb.cases().size());
        CASES.write(jdbc, rows(efrsb.cases()), report);
        counts.put(EFRSB_EVENTS.name(), efrsb.events().size());
        EFRSB_EVENTS.write(jdbc, rows(efrsb.events()), report);
        return counts;
    }

    private static List<SourceUpsert.Row> rows(final List<MapSqlParameterSource> values) {
        final List<SourceUpsert.Row> rows = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            rows.add(new SourceUpsert.Row(i + 2, values.get(i)));
        }
        return rows;
    }

    private void ensureNoRunningRun() {
        final Integer running = jdbc.queryForObject("select count(*) from run where status = 'RUNNING'", Map.of(), Integer.class);
        if (running != null && running > 0) {
            throw new ApiException("RUN_ALREADY_RUNNING", HttpStatus.CONFLICT, "Прогон уже выполняется: импорт невозможен", Map.of());
        }
    }

    private static ApiException invalid(final ImportValidator v) {
        return new ApiException("IMPORT_INVALID", HttpStatus.BAD_REQUEST, "Выгрузка не прошла проверку: ошибок " + v.errors().size(),
                Map.of("errors", v.errors()));
    }

    // ------------------------------------------------------------------ файлы

    /**
     * Папка выгрузки - та, где лежит manifest.json: сам каталог или единственная вложенная папка (ZIP с папкой внутри).
     */
    private static Path findRoot(final Path dir) {
        if (Files.exists(dir.resolve(MANIFEST))) {
            return dir;
        }
        try (Stream<Path> files = Files.walk(dir, 2)) {
            return files.filter(path -> path.getFileName().toString().equals(MANIFEST))
                    .map(Path::getParent)
                    .findFirst()
                    .orElse(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * index.json набора партий: в самом каталоге или в единственной вложенной папке (ZIP с папкой delivery внутри);
     * null - это одна выгрузка.
     */
    private static Path findIndex(final Path dir) {
        if (Files.isRegularFile(dir.resolve(INDEX))) {
            return dir.resolve(INDEX);
        }
        if (Files.exists(dir.resolve(MANIFEST))) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            final List<Path> nested = files.filter(Files::isDirectory).toList();
            return nested.size() == 1 && Files.isRegularFile(nested.getFirst().resolve(INDEX)) ? nested.getFirst().resolve(INDEX) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path unzip(final InputStream zip) {
        try {
            final Path dir = Files.createTempDirectory("manual-upload");
            try (ZipInputStream in = new ZipInputStream(zip)) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    final Path target = dir.resolve(entry.getName()).normalize();
                    if (!target.startsWith(dir)) {
                        throw ApiException.validation("Недопустимый путь в архиве: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return dir;
        } catch (IOException e) {
            throw ApiException.validation("Не ZIP-архив: " + e.getMessage());
        }
    }

    private static void deleteQuietly(final Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            log.warn("Не удалить временный каталог {}", dir, e);
        }
    }

    // ------------------------------------------------------------------ значения

    private static LocalDate max(final LocalDate a, final LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static LocalDate date(final Object value) {
        try {
            return value == null || value.toString().isBlank() ? null : LocalDate.parse(value.toString().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal decimal(final String value) {
        return value == null ? null : new BigDecimal(value.trim());
    }

    private static Integer integer(final String value) {
        return value == null ? null : new BigDecimal(value.trim()).intValueExact();
    }

    private static Boolean bool(final String value) {
        return value == null ? null : Boolean.parseBoolean(value.trim());
    }

    private static String stringValue(final Object value) {
        return value == null ? null : value.toString();
    }

    @FunctionalInterface
    private interface RowBinder {
        void bind(Csv.Row row, MapSqlParameterSource params);
    }

    record Tables(Csv.Table managers, Csv.Table clients, Csv.Table buyers, Csv.Table financials, Csv.Table contracts,
                          Csv.Table upds, Csv.Table payments) {

        boolean isEmpty() {
            return Stream.of(managers, clients, buyers, financials, contracts, upds, payments).allMatch(table -> table.rows().isEmpty());
        }
    }

    /**
     * Уже загруженные коды менеджеров, ИНН клиентов и договоры (клиент|номер → ИНН покупателя).
     */
    private record Existing(Set<String> managers, Set<String> clients, Map<String, String> contracts) {
    }

    /**
     * Проверенная выгрузка, готовая к записи: вид и паспорт для manual_upload_batch, таблицы, пропускаемые УПД.
     */
    private record Prepared(String kind, Map<String, Object> source, String formatVersion, Long seed, LocalDate snapshotDate,
                            String sourceName, Tables tables, EfrsbTables efrsb, Set<Integer> skippedUpds) {
    }

    /**
     * Партия набора: папка (префикс файлов в отчёте), проверенные данные, её отчёт.
     */
    private record Part(String folder, Prepared prepared, ImportReport report) {
    }

    record EfrsbTables(List<MapSqlParameterSource> statuses, List<MapSqlParameterSource> cases,
                               List<MapSqlParameterSource> events, LocalDate snapshotDate) {
    }
}
