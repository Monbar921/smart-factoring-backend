package ru.smart.factoring.backend.factoring.source;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Партия данных ML-команды (контракт данных v1.0, smart-factoring-ml ml/delay/delivery/batch_NN, Р-54): CSV через запятую,
 * клиенты и договоры связаны по client_id и contract_id, статусы покупателей - в buyers_status.csv.
 * <p>
 * Партия переводится в таблицы формата генератора (manual-upload-format.md) и дальше импортируется как выгрузка генератора:
 * те же проверки ссылок и та же дельта (Р-52). Чего в партии нет, достраивается:
 * менеджеры - по manager_id клиентов, наименование покупателя - по ИНН, дата регистрации - по возрасту на дату снимка,
 * банкротство по ЕФРСБ - незакрытое дело без номера на стадии «Наблюдение» с даты события.
 * Отчётности покупателей в партии нет: готовый PD из buyers.csv записывается с версией модели из manifest.json
 * и используется как кэш PD этой версии (блок 1 переносит его в buyer_pd_cache).
 */
final class DeliveryBatch {
    static final String CONTRACT_VERSION = "1.0";
    private static final char SEPARATOR = ',';
    private static final String MANIFEST = "manifest.json";
    private static final String CLIENTS = "clients.csv";
    private static final String BUYERS = "buyers.csv";
    private static final String STATUSES = "buyers_status.csv";
    private static final String CONTRACTS = "contracts.csv";
    private static final String UPDS = "upd.csv";
    private static final String PAYMENTS = "payments.csv";
    private static final Map<String, String> INDUSTRIES = Map.of(
            "торговля", "TRADE", "производство", "MANUFACTURING", "профессиональные_услуги", "PROFESSIONAL",
            "строительство", "CONSTRUCTION");
    private static final String ACTIVE = "действует";
    /** причина «мало данных» партии - в коды сервиса моделей (П3-5.1); неизвестная остаётся как есть и не пройдёт проверку */
    private static final Map<String, String> LOW_DATA_REASONS = Map.of(
            "нет_отчётности", "NO_RECENT_REPORT", "выручка_ниже_порога", "LOW_REVENUE", "возраст_до_года", "YOUNG_COMPANY");
    private static final String NO_CASE_NUMBER = "б/н";
    private static final String FIRST_STAGE = "Наблюдение";
    private static final List<String> CASE_COLUMNS = List.of("court", "applicant", "start_date_source", "observation_from",
            "rehabilitation_from", "external_management_from", "declared_bankrupt_on", "receivership_from", "end_date", "outcome",
            "first_intent_date");

    private DeliveryBatch() {
    }

    /**
     * Партия ML-команды отличается от выгрузки генератора полем contract_version в manifest.json.
     */
    static boolean matches(final Map<String, Object> manifest) {
        return manifest.containsKey("contract_version");
    }

    static Converted read(final Path root, final Map<String, Object> manifest, final ImportValidator v) {
        if (!CONTRACT_VERSION.equals(string(manifest.get("contract_version")))) {
            v.error(MANIFEST, null, "contract_version должен быть " + CONTRACT_VERSION);
        }
        final LocalDate end = date(manifest.get("D_end"));
        if (end == null) {
            v.error(MANIFEST, null, "нет D_end");
        }
        final Map<?, ?> declared = manifest.get("files") instanceof Map<?, ?> files ? files : Map.of();
        final Csv.Table clients = table(root, CLIENTS, declared, v, List.of("client_id", "client_name", "client_inn", "industry",
                "abs_liquidity", "core", "manager_id", "edo_consent"));
        final Csv.Table buyers = table(root, BUYERS, declared, v, List.of("buyer_inn", "snapshot_date"));
        final Csv.Table statuses = table(root, STATUSES, declared, v, List.of("buyer_inn", "egrul_status", "bankruptcy_efrsb"));
        final Csv.Table contracts = table(root, CONTRACTS, declared, v, List.of("contract_id", "contract_number", "client_id",
                "buyer_inn", "contract_date", "deferral_days"));
        final Csv.Table upds = table(root, UPDS, declared, v, List.of("upd_id", "upd_number", "upd_date", "client_id", "buyer_inn",
                "contract_id", "amount_rub"));
        final Csv.Table payments = table(root, PAYMENTS, declared, v, List.of("payment_id", "pay_date", "client_id", "buyer_inn",
                "amount_rub"));

        // партия клиента - для выбора клиентов прогона по партиям (Р-62)
        final String batchId = string(manifest.get("batch_id"));
        // client_id и contract_id - внутренние ключи партии: в таблицы попадают ИНН клиента и номер договора.
        // Неизвестный id остаётся как есть, и проверка ссылок выгрузки сообщит, что такого клиента или договора нет
        final Map<String, String> clientInn = new HashMap<>();
        final Set<String> managers = new LinkedHashSet<>();
        final List<Csv.Row> clientRows = new ArrayList<>();
        for (final Csv.Row row : clients.rows()) {
            if (row.get("client_id") != null && clientInn.put(row.get("client_id"), row.get("client_inn")) != null) {
                v.error(CLIENTS, row.line(), "повтор client_id " + row.get("client_id"));
            }
            if (row.get("manager_id") != null) {
                managers.add(row.get("manager_id"));
            }
            clientRows.add(row(row, "inn", row.get("client_inn"), "name", row.get("client_name"),
                    "industry", INDUSTRIES.getOrDefault(row.get("industry"), row.get("industry")), "region", row.get("region"),
                    "abs_liquidity", row.get("abs_liquidity"), "ar_turnover_days", days(row.get("receivable_days")),
                    "is_core", bool(row.get("core")), "manager_code", row.get("manager_id"),
                    "edo_consent", bool(row.get("edo_consent")), "factoring_client", "false", "batch_id", batchId));
        }
        final List<Csv.Row> managerRows = new ArrayList<>();
        int line = 1;
        for (final String code : managers) {
            managerRows.add(new Csv.Row(++line, Map.of("code", code, "full_name", "Менеджер " + code)));
        }

        final String pdVersion = string(manifest.get("pd_model_version"));
        LocalDate snapshot = null;
        final List<Csv.Row> buyerRows = new ArrayList<>();
        for (final Csv.Row row : buyers.rows()) {
            final LocalDate snapshotDate = v.date(BUYERS, row, "snapshot_date", true);
            snapshot = max(snapshot, snapshotDate);
            final Integer age = v.integer(BUYERS, row, "age_years", false);
            final BigDecimal pd = v.decimal(BUYERS, row, "pd", false);
            final String reason = row.get("low_data_reason");
            if (reason != null && !LOW_DATA_REASONS.containsKey(reason)) {
                v.error(BUYERS, row.line(), "low_data_reason: ожидается одно из " + LOW_DATA_REASONS.keySet() + ": " + reason);
            }
            final String inn = row.get("buyer_inn");
            buyerRows.add(row(row, "inn", inn, "name", row.get("buyer_name") != null ? row.get("buyer_name") : "ИНН " + inn,
                    "okved_section", row.get("okved_section"), "region", row.get("region"),
                    "registration_date", age != null && snapshotDate != null ? snapshotDate.minusYears(age).toString() : null,
                    "data_date", row.get("snapshot_date"),
                    "pd", row.get("pd"), "pd_model_version", pd != null ? pdVersion : null,
                    "low_data_reason", reason != null ? LOW_DATA_REASONS.get(reason) : null));
        }

        final Map<String, String> contractNumber = new HashMap<>();
        final List<Csv.Row> contractRows = new ArrayList<>();
        for (final Csv.Row row : contracts.rows()) {
            if (row.get("contract_id") != null && contractNumber.put(row.get("contract_id"), row.get("contract_number")) != null) {
                v.error(CONTRACTS, row.line(), "повтор contract_id " + row.get("contract_id"));
            }
            contractRows.add(row(row, "client_inn", inn(clientInn, row), "buyer_inn", row.get("buyer_inn"),
                    "number", row.get("contract_number"), "contract_date", row.get("contract_date"),
                    "deferral_days", row.get("deferral_days")));
        }
        final List<Csv.Row> updRows = new ArrayList<>();
        for (final Csv.Row row : upds.rows()) {
            updRows.add(row(row, "client_inn", inn(clientInn, row), "buyer_inn", row.get("buyer_inn"),
                    "contract_number", contractNumber.getOrDefault(row.get("contract_id"), row.get("contract_id")),
                    "number", row.get("upd_number"), "upd_date", row.get("upd_date"), "amount", row.get("amount_rub")));
        }
        final List<Csv.Row> paymentRows = new ArrayList<>();
        for (final Csv.Row row : payments.rows()) {
            paymentRows.add(row(row, "source_id", row.get("payment_id"), "client_inn", inn(clientInn, row),
                    "payer_inn", row.get("buyer_inn"), "amount", row.get("amount_rub"), "payment_date", row.get("pay_date"),
                    "purpose", row.get("purpose")));
        }

        final List<MapSqlParameterSource> egrul = new ArrayList<>();
        final List<MapSqlParameterSource> cases = new ArrayList<>();
        for (final Csv.Row row : statuses.rows()) {
            final String inn = v.required(STATUSES, row, "buyer_inn");
            final String status = v.required(STATUSES, row, "egrul_status");
            final LocalDate eventDate = v.date(STATUSES, row, "event_date", false);
            snapshot = max(snapshot, v.date(STATUSES, row, "snapshot_date", false));
            final String bankruptcy = v.required(STATUSES, row, "bankruptcy_efrsb");
            if (bankruptcy != null && !"0".equals(bankruptcy) && !"1".equals(bankruptcy)) {
                v.error(STATUSES, row.line(), "bankruptcy_efrsb: ожидается 0 или 1: " + bankruptcy);
            }
            if (inn == null) {
                continue;
            }
            egrul.add(new MapSqlParameterSource()
                    .addValue("inn", inn)
                    .addValue("ogrn", null)
                    .addValue("name", null)
                    .addValue("region", null)
                    .addValue("debtor_category", null)
                    .addValue("status", status)
                    .addValue("status_date", ACTIVE.equals(status) ? null : eventDate));
            if ("1".equals(bankruptcy)) {
                final MapSqlParameterSource bankruptcyCase = new MapSqlParameterSource()
                        .addValue("inn", inn)
                        .addValue("case_number", NO_CASE_NUMBER)
                        .addValue("stage", FIRST_STAGE)
                        .addValue("start_date", eventDate);
                CASE_COLUMNS.forEach(column -> bankruptcyCase.addValue(column, null));
                cases.add(bankruptcyCase);
            }
        }

        final LocalDate snapshotDate = snapshot != null ? snapshot : end;
        final Map<String, Object> converted = new LinkedHashMap<>();
        converted.put("format_version", ManualUploadService.FORMAT_VERSION);
        converted.put("data_date", end != null ? end.toString() : null);
        converted.put("snapshot_date", snapshotDate != null ? snapshotDate.toString() : null);
        converted.put("seed", manifest.get("seed"));
        converted.put("generator", string(manifest.get("batch_id")) + " " + string(manifest.get("generator_version")));
        return new Converted(converted,
                new ManualUploadService.Tables(tableOf(managerRows), tableOf(clientRows), tableOf(buyerRows), tableOf(List.of()),
                        tableOf(contractRows), tableOf(updRows), tableOf(paymentRows)),
                new ManualUploadService.EfrsbTables(egrul, cases, List.of(), snapshotDate));
    }

    /**
     * Файл партии обязателен; число строк и SHA-256 сверяются с manifest.json, если они там указаны.
     */
    private static Csv.Table table(final Path root, final String name, final Map<?, ?> declared, final ImportValidator v,
                                   final List<String> required) {
        final Path file = root.resolve(name);
        if (!Files.exists(file)) {
            v.error(name, null, "нет файла: партия загружается целиком");
            return tableOf(List.of());
        }
        final Csv.Table table = Csv.read(file, SEPARATOR);
        if (declared.get(name) instanceof Map<?, ?> meta) {
            if (meta.get("rows") instanceof Number rows && rows.intValue() != table.rows().size()) {
                v.error(name, null, "строк " + table.rows().size() + ", в manifest.json - " + rows);
            }
            if (meta.get("sha256") instanceof String sha256 && !sha256.equalsIgnoreCase(sha256(file))) {
                v.error(name, null, "SHA-256 не совпадает с manifest.json: файл изменён или повреждён");
            }
        }
        return v.columns(name, table, required) ? table : tableOf(List.of());
    }

    private static String sha256(final Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Csv.Table tableOf(final List<Csv.Row> rows) {
        return new Csv.Table(rows.isEmpty() ? List.of() : List.copyOf(rows.getFirst().values().keySet()), rows);
    }

    /**
     * Строка в колонках формата генератора; номер строки - из файла партии, для сообщений.
     */
    private static Csv.Row row(final Csv.Row source, final String... columnsAndValues) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            values.put(columnsAndValues[i], columnsAndValues[i + 1]);
        }
        return new Csv.Row(source.line(), values);
    }

    private static String inn(final Map<String, String> clientInn, final Csv.Row row) {
        return clientInn.getOrDefault(row.get("client_id"), row.get("client_id"));
    }

    /**
     * 1/0 партии - true/false формата генератора; иное значение остаётся и не пройдёт проверку.
     */
    private static String bool(final String value) {
        if ("1".equals(value)) {
            return "true";
        }
        return "0".equals(value) ? "false" : value;
    }

    /**
     * Оборачиваемость дебиторки в партии дробная, в таблице - целые дни.
     */
    private static String days(final String value) {
        try {
            return value == null ? null : new BigDecimal(value.trim()).setScale(0, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static LocalDate max(final LocalDate a, final LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private static LocalDate date(final Object value) {
        try {
            return value == null ? null : LocalDate.parse(value.toString().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String string(final Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Партия в формате генератора: manifest (format_version, data_date, snapshot_date, seed, generator), таблицы, статусы.
     */
    record Converted(Map<String, Object> manifest, ManualUploadService.Tables tables, ManualUploadService.EfrsbTables efrsb) {
    }
}
