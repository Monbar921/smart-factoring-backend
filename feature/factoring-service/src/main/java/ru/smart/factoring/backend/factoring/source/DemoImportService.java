package ru.smart.factoring.backend.factoring.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.smart.factoring.backend.api.demo.DemoImportResultDto;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Импорт демо-данных в таблицы demo_* (ДД-1): выгрузка генератора и events.csv
 * (формат - docs/claude/module-0/demo-dataset-format.md).
 * <p>
 * Выгрузка генератора - полный снимок демо-набора: импорт заменяет источник целиком и сбрасывает загруженные данные
 * и результаты прогонов (Р-30). Импорт events.csv заменяет только внешние данные ЕГРЮЛ/ЕФРСБ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoImportService {
    static final String FORMAT_VERSION = "1";
    private static final String MANIFEST = "manifest.json";
    private static final String EVENTS = "events.csv";
    private static final Set<String> INDUSTRIES = Set.of("CONSTRUCTION", "PROFESSIONAL", "MANUFACTURING", "TRADE");
    private static final List<String> FINANCIAL_COLUMNS = List.of(
            "assets_total", "current_assets", "receivables", "cash", "equity", "long_term_borrowings", "short_term_liabilities",
            "short_term_borrowings", "payables", "revenue", "cost_of_sales", "sales_profit", "interest_payable", "net_profit");
    /**
     * Загруженные данные и результаты прогонов: сбрасываются при импорте нового демо-набора (Р-30).
     */
    private static final List<String> LOADED_AND_RESULTS = List.of(
            "run_log", "notification", "offer", "upd_decision", "upd_score", "buyer_score", "buyer_pd_cache", "upd_state",
            "payment_allocation", "run_step", "payment", "upd", "contract", "buyer_financials", "buyer_external_data",
            "client", "buyer", "manager", "run", "demo_factoring_package", "demo_risk_signal");
    private static final List<String> GENERATOR_SOURCE = List.of(
            "demo_manager", "demo_client", "demo_buyer", "demo_buyer_financials", "demo_contract", "demo_upd", "demo_payment");
    private static final List<String> EFRSB_SOURCE = List.of("demo_egrul_status", "demo_bankruptcy_case", "demo_efrsb_event");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final DemoProperties properties;
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

    public DemoImportResultDto importGenerator(final Path dir) {
        final Path root = findRoot(dir);
        final ImportValidator v = new ImportValidator();
        final Map<String, Object> manifest = readManifest(root, v);
        final Tables tables = readGeneratorTables(root, v);
        final EfrsbTables efrsb = Files.exists(root.resolve(EVENTS)) ? readEfrsb(root.resolve(EVENTS), v) : null;
        final LocalDate dataDate = validateGenerator(tables, manifest, v);
        if (v.hasErrors()) {
            throw invalid(v);
        }
        final LocalDate snapshotDate = date(manifest.get("snapshot_date"));
        return transactionTemplate.execute(status -> {
            ensureNoRunningRun();
            truncate(LOADED_AND_RESULTS);
            truncate(GENERATOR_SOURCE);
            final Map<String, Integer> counts = insertGenerator(tables);
            if (efrsb != null) {
                truncate(EFRSB_SOURCE);
                counts.putAll(insertEfrsb(efrsb));
            }
            final long batchId = insertBatch("GENERATOR", stringValue(manifest.get("format_version")),
                    manifest.get("seed") instanceof Number seed ? seed.longValue() : null, dataDate, snapshotDate,
                    stringValue(manifest.get("generator")), counts);
            log.info("Импорт демо-набора {}: {}", batchId, counts);
            return new DemoImportResultDto(batchId, dataDate, snapshotDate, counts);
        });
    }

    public DemoImportResultDto importEfrsb(final Path file) {
        final ImportValidator v = new ImportValidator();
        final EfrsbTables efrsb = readEfrsb(file, v);
        if (v.hasErrors()) {
            throw invalid(v);
        }
        return transactionTemplate.execute(status -> {
            ensureNoRunningRun();
            truncate(EFRSB_SOURCE);
            final Map<String, Integer> counts = insertEfrsb(efrsb);
            final long batchId = insertBatch("EFRSB", null, null, null, efrsb.snapshotDate(), file.getFileName().toString(), counts);
            return new DemoImportResultDto(batchId, null, efrsb.snapshotDate(), counts);
        });
    }

    /**
     * ZIP с выгрузкой генератора: распаковывается во временный каталог и импортируется как папка.
     */
    public DemoImportResultDto importGeneratorZip(final InputStream zip) {
        final Path dir = unzip(zip);
        try {
            return importGenerator(dir);
        } finally {
            deleteQuietly(dir);
        }
    }

    public DemoImportResultDto importEfrsbFile(final InputStream content) {
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

    @SuppressWarnings("checkstyle:IllegalCatch")
    private Map<String, Object> readManifest(final Path root, final ImportValidator v) {
        final Path file = root.resolve(MANIFEST);
        if (!Files.exists(file)) {
            v.error(MANIFEST, null, "нет файла");
            return Map.of();
        }
        try {
            final Map<String, Object> manifest = json.readMap(Files.readString(file));
            if (!FORMAT_VERSION.equals(stringValue(manifest.get("format_version")))) {
                v.error(MANIFEST, null, "format_version должен быть " + FORMAT_VERSION);
            }
            return manifest;
        } catch (IOException | RuntimeException e) {
            v.error(MANIFEST, null, "не JSON: " + e.getMessage());
            return Map.of();
        }
    }

    private Tables readGeneratorTables(final Path root, final ImportValidator v) {
        return new Tables(
                table(root, "managers.csv", v, List.of("code", "full_name"), true),
                table(root, "clients.csv", v, List.of("inn", "name", "industry", "abs_liquidity", "is_core", "manager_code",
                        "edo_consent", "factoring_client"), true),
                table(root, "buyers.csv", v, List.of("inn", "name"), true),
                table(root, "buyer_financials.csv", v, List.of("inn", "year"), false),
                table(root, "contracts.csv", v, List.of("client_inn", "buyer_inn", "number", "contract_date", "deferral_days"), true),
                table(root, "upds.csv", v, List.of("client_inn", "buyer_inn", "contract_number", "number", "upd_date", "amount"), true),
                table(root, "payments.csv", v, List.of("source_id", "client_inn", "payer_inn", "amount", "payment_date"), true));
    }

    private static Csv.Table table(final Path root, final String name, final ImportValidator v, final List<String> required,
                                   final boolean mandatory) {
        final Path file = root.resolve(name);
        if (!Files.exists(file)) {
            if (mandatory) {
                v.error(name, null, "нет файла");
            }
            return new Csv.Table(List.of(), List.of());
        }
        final Csv.Table table = Csv.read(file);
        return v.columns(name, table, required) ? table : new Csv.Table(List.of(), List.of());
    }

    private LocalDate validateGenerator(final Tables t, final Map<String, Object> manifest, final ImportValidator v) {
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
            if (manager != null && !managers.contains(manager)) {
                v.error(file, row.line(), "нет менеджера " + manager + " в managers.csv");
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
        final Map<String, String> contractBuyer = new HashMap<>();
        for (final Csv.Row row : t.contracts().rows()) {
            final String file = "contracts.csv";
            final String client = v.required(file, row, "client_inn");
            final String number = v.required(file, row, "number");
            final String key = client + "|" + number;
            if (contractBuyer.put(key, v.required(file, row, "buyer_inn")) != null) {
                v.error(file, row.line(), "повтор ключа " + key);
            }
            if (client != null && !clients.contains(client)) {
                v.error(file, row.line(), "нет клиента " + client + " в clients.csv");
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
                v.error(file, row.line(), "нет клиента " + client + " в clients.csv");
            }
            final String contractOwner = contractBuyer.get(client + "|" + contract);
            if (contractOwner == null || !contractOwner.equals(buyer)) {
                v.error(file, row.line(), "нет договора " + contract + " пары " + client + " - " + buyer + " в contracts.csv");
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
                v.error(file, row.line(), "нет клиента " + client + " в clients.csv");
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
        return latest;
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
                    .addValue("debtorCategory", row.get("Категория должника"))
                    .addValue("status", row.get("Статус в ЕГРЮЛ"))
                    .addValue("statusDate", statusDate));
            final String caseNumber = row.get("Номер дела");
            if (caseNumber != null) {
                cases.computeIfAbsent(inn + "|" + caseNumber, key -> new MapSqlParameterSource()
                        .addValue("inn", inn)
                        .addValue("caseNumber", caseNumber)
                        .addValue("court", row.get("Суд"))
                        .addValue("applicant", row.get("Заявитель"))
                        .addValue("stage", row.get("Статус дела"))
                        .addValue("startDate", v.date(name, row, "Дата начала дела", false))
                        .addValue("startDateSource", row.get("Источник даты начала"))
                        .addValue("observationFrom", v.date(name, row, "Наблюдение с", false))
                        .addValue("rehabilitationFrom", v.date(name, row, "Финансовое оздоровление с", false))
                        .addValue("externalManagementFrom", v.date(name, row, "Внешнее управление с", false))
                        .addValue("declaredBankruptOn", v.date(name, row, "Признан банкротом", false))
                        .addValue("receivershipFrom", v.date(name, row, "Конкурсное производство с", false))
                        .addValue("endDate", v.date(name, row, "Дата окончания дела", false))
                        .addValue("outcome", row.get("Итог дела"))
                        .addValue("firstIntentDate", v.date(name, row, "Первое намерение/заявление о банкротстве", false)));
            }
            final String message = v.required(name, row, "Номер сообщения");
            v.unique(name, row, eventKeys, String.join("|", inn, Objects.toString(caseNumber, ""), Objects.toString(message, ""),
                    Objects.toString(row.get("Тип события"), ""), Objects.toString(row.get("Событие"), "")));
            final LocalDate eventDate = v.date(name, row, "Дата события", false);
            final LocalDate publishedOn = v.date(name, row, "Дата публикации", false);
            events.add(new MapSqlParameterSource()
                    .addValue("inn", inn)
                    .addValue("caseNumber", caseNumber)
                    .addValue("eventType", row.get("Тип события"))
                    .addValue("event", row.get("Событие"))
                    .addValue("details", row.get("Детали"))
                    .addValue("eventDate", eventDate)
                    .addValue("publishedOn", publishedOn)
                    .addValue("messageNumber", message)
                    .addValue("annulled", "да".equalsIgnoreCase(Objects.toString(row.get("Аннулировано"), "").trim()))
                    .addValue("url", row.get("Ссылка")));
            snapshot = max(max(max(snapshot, statusDate), eventDate), publishedOn);
        }
        return new EfrsbTables(new ArrayList<>(statuses.values()), new ArrayList<>(cases.values()), events, snapshot);
    }

    // ------------------------------------------------------------------ запись

    private Map<String, Integer> insertGenerator(final Tables t) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("managers", batch("insert into demo_manager (code, full_name) values (:code, :full_name)",
                t.managers(), (row, p) -> p.addValue("code", row.get("code")).addValue("full_name", row.get("full_name"))));
        counts.put("clients", batch("""
                        insert into demo_client (inn, name, industry, okved_section, region, abs_liquidity, ar_turnover_days, is_core,
                                                 manager_code, edo_consent, factoring_client, control_case)
                        values (:inn, :name, :industry, :okved_section, :region, :abs_liquidity, :ar_turnover_days, :is_core,
                                :manager_code, :edo_consent, :factoring_client, :control_case)""",
                t.clients(), (row, p) -> p.addValue("inn", row.get("inn")).addValue("name", row.get("name"))
                        .addValue("industry", row.get("industry")).addValue("okved_section", row.get("okved_section"))
                        .addValue("region", row.get("region")).addValue("abs_liquidity", decimal(row.get("abs_liquidity")))
                        .addValue("ar_turnover_days", integer(row.get("ar_turnover_days")))
                        .addValue("is_core", bool(row.get("is_core"))).addValue("manager_code", row.get("manager_code"))
                        .addValue("edo_consent", bool(row.get("edo_consent")))
                        .addValue("factoring_client", bool(row.get("factoring_client")))
                        .addValue("control_case", row.get("control_case"))));
        counts.put("buyers", batch("""
                        insert into demo_buyer (inn, ogrn, name, okved, okved_section, region, registration_date, data_date)
                        values (:inn, :ogrn, :name, :okved, :okved_section, :region, :registration_date, :data_date)""",
                t.buyers(), (row, p) -> p.addValue("inn", row.get("inn")).addValue("ogrn", row.get("ogrn"))
                        .addValue("name", row.get("name")).addValue("okved", row.get("okved"))
                        .addValue("okved_section", row.get("okved_section")).addValue("region", row.get("region"))
                        .addValue("registration_date", date(row.get("registration_date")))
                        .addValue("data_date", date(row.get("data_date")))));
        counts.put("buyerFinancials", batch("""
                        insert into demo_buyer_financials (inn, year, simplified, %s, data_date)
                        values (:inn, :year, :simplified, %s, :data_date)""".formatted(String.join(", ", FINANCIAL_COLUMNS),
                        String.join(", ", FINANCIAL_COLUMNS.stream().map(c -> ":" + c).toList())),
                t.financials(), (row, p) -> {
                    p.addValue("inn", row.get("inn")).addValue("year", integer(row.get("year")))
                            .addValue("simplified", Boolean.TRUE.equals(bool(row.get("simplified"))))
                            .addValue("data_date", date(row.get("data_date")));
                    FINANCIAL_COLUMNS.forEach(column -> p.addValue(column, decimal(row.get(column))));
                }));
        counts.put("contracts", batch("""
                        insert into demo_contract (client_inn, buyer_inn, number, contract_date, deferral_days)
                        values (:client_inn, :buyer_inn, :number, :contract_date, :deferral_days)""",
                t.contracts(), (row, p) -> p.addValue("client_inn", row.get("client_inn")).addValue("buyer_inn", row.get("buyer_inn"))
                        .addValue("number", row.get("number")).addValue("contract_date", date(row.get("contract_date")))
                        .addValue("deferral_days", integer(row.get("deferral_days")))));
        counts.put("upds", batch("""
                        insert into demo_upd (client_inn, buyer_inn, contract_number, number, upd_date, amount, control_case)
                        values (:client_inn, :buyer_inn, :contract_number, :number, :upd_date, :amount, :control_case)""",
                t.upds(), (row, p) -> p.addValue("client_inn", row.get("client_inn")).addValue("buyer_inn", row.get("buyer_inn"))
                        .addValue("contract_number", row.get("contract_number")).addValue("number", row.get("number"))
                        .addValue("upd_date", date(row.get("upd_date"))).addValue("amount", decimal(row.get("amount")))
                        .addValue("control_case", row.get("control_case"))));
        counts.put("payments", batch("""
                        insert into demo_payment (source_id, client_inn, payer_inn, amount, payment_date, purpose, control_case)
                        values (:source_id, :client_inn, :payer_inn, :amount, :payment_date, :purpose, :control_case)""",
                t.payments(), (row, p) -> p.addValue("source_id", row.get("source_id")).addValue("client_inn", row.get("client_inn"))
                        .addValue("payer_inn", row.get("payer_inn")).addValue("amount", decimal(row.get("amount")))
                        .addValue("payment_date", date(row.get("payment_date"))).addValue("purpose", row.get("purpose"))
                        .addValue("control_case", row.get("control_case"))));
        return counts;
    }

    private Map<String, Integer> insertEfrsb(final EfrsbTables efrsb) {
        jdbc.batchUpdate("""
                insert into demo_egrul_status (inn, ogrn, name, region, debtor_category, status, status_date)
                values (:inn, :ogrn, :name, :region, :debtorCategory, :status, :statusDate)""",
                efrsb.statuses().toArray(MapSqlParameterSource[]::new));
        jdbc.batchUpdate("""
                insert into demo_bankruptcy_case (inn, case_number, court, applicant, stage, start_date, start_date_source,
                    observation_from, rehabilitation_from, external_management_from, declared_bankrupt_on, receivership_from,
                    end_date, outcome, first_intent_date)
                values (:inn, :caseNumber, :court, :applicant, :stage, :startDate, :startDateSource, :observationFrom,
                    :rehabilitationFrom, :externalManagementFrom, :declaredBankruptOn, :receivershipFrom, :endDate, :outcome,
                    :firstIntentDate)""",
                efrsb.cases().toArray(MapSqlParameterSource[]::new));
        jdbc.batchUpdate("""
                insert into demo_efrsb_event (inn, case_number, event_type, event, details, event_date, published_on,
                    message_number, annulled, url)
                values (:inn, :caseNumber, :eventType, :event, :details, :eventDate, :publishedOn, :messageNumber, :annulled, :url)""",
                efrsb.events().toArray(MapSqlParameterSource[]::new));
        final Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("egrulStatuses", efrsb.statuses().size());
        counts.put("bankruptcyCases", efrsb.cases().size());
        counts.put("efrsbEvents", efrsb.events().size());
        return counts;
    }

    private int batch(final String sql, final Csv.Table table, final RowBinder binder) {
        final MapSqlParameterSource[] batch = table.rows().stream()
                .map(row -> {
                    final MapSqlParameterSource params = new MapSqlParameterSource();
                    binder.bind(row, params);
                    return params;
                })
                .toArray(MapSqlParameterSource[]::new);
        try {
            jdbc.batchUpdate(sql, batch);
        } catch (DataAccessException e) {
            // ограничения БД, которые не проверил валидатор (например, длина значения): отказ всей выгрузки
            throw new ApiException("IMPORT_INVALID", HttpStatus.BAD_REQUEST, "Выгрузка не записалась в БД",
                    Map.of("errors", List.of(String.valueOf(e.getMostSpecificCause().getMessage()))));
        }
        return batch.length;
    }

    private long insertBatch(final String kind, final String formatVersion, final Long seed, final LocalDate dataDate,
                             final LocalDate snapshotDate, final String sourceName, final Map<String, Integer> counts) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                        insert into demo_import_batch (kind, format_version, seed, data_date, snapshot_date, source_name, row_counts)
                        values (:kind, :formatVersion, :seed, :dataDate, :snapshotDate, :sourceName, cast(:rowCounts as jsonb))
                        returning id""",
                new MapSqlParameterSource()
                        .addValue("kind", kind)
                        .addValue("formatVersion", formatVersion)
                        .addValue("seed", seed)
                        .addValue("dataDate", dataDate)
                        .addValue("snapshotDate", snapshotDate)
                        .addValue("sourceName", sourceName)
                        .addValue("rowCounts", json.write(counts)),
                Long.class));
    }

    private void ensureNoRunningRun() {
        final Integer running = jdbc.queryForObject("select count(*) from run where status = 'RUNNING'", Map.of(), Integer.class);
        if (running != null && running > 0) {
            throw new ApiException("RUN_ALREADY_RUNNING", HttpStatus.CONFLICT, "Прогон уже выполняется: импорт невозможен", Map.of());
        }
    }

    private void truncate(final List<String> tables) {
        jdbc.getJdbcTemplate().execute("truncate table " + String.join(", ", tables) + " restart identity cascade");
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

    private static Path unzip(final InputStream zip) {
        try {
            final Path dir = Files.createTempDirectory("demo-import");
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

    private record Tables(Csv.Table managers, Csv.Table clients, Csv.Table buyers, Csv.Table financials, Csv.Table contracts,
                          Csv.Table upds, Csv.Table payments) {
    }

    private record EfrsbTables(List<MapSqlParameterSource> statuses, List<MapSqlParameterSource> cases,
                               List<MapSqlParameterSource> events, LocalDate snapshotDate) {
    }
}
