package ru.smart.factoring.backend.manualupload;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Фикстура manual-upload/fixture-v1 соответствует формату docs/claude/module-0/manual-upload-format.md:
 * заголовки, ключи, ссылки, дата данных и покрытие контрольных случаев (CASES.md).
 */
class ManualUploadFixtureFormatTest {

    private static final String DIR = "/manual-upload/fixture-v1/";

    @Test
    void filesHaveRequiredColumns() {
        assertThat(read("managers.csv").getFirst().keySet()).contains("code", "full_name");
        assertThat(read("clients.csv").getFirst().keySet()).contains("inn", "name", "industry", "abs_liquidity", "is_core",
                "manager_code", "edo_consent", "factoring_client");
        assertThat(read("buyers.csv").getFirst().keySet()).contains("inn", "name");
        assertThat(read("buyer_financials.csv").getFirst().keySet()).contains("inn", "year", "simplified", "revenue");
        assertThat(read("contracts.csv").getFirst().keySet()).contains("client_inn", "buyer_inn", "number", "contract_date",
                "deferral_days");
        assertThat(read("upds.csv").getFirst().keySet()).contains("client_inn", "buyer_inn", "contract_number", "number",
                "upd_date", "amount");
        assertThat(read("payments.csv").getFirst().keySet()).contains("source_id", "client_inn", "payer_inn", "amount",
                "payment_date", "purpose");
        assertThat(read("events.csv").getFirst().keySet()).contains("ИНН", "Статус в ЕГРЮЛ", "Номер дела", "Статус дела",
                "Номер сообщения");
    }

    @Test
    void keysAreUnique() {
        assertUnique("clients.csv", "inn");
        assertUnique("buyers.csv", "inn");
        assertUnique("buyer_financials.csv", "inn", "year");
        assertUnique("contracts.csv", "client_inn", "number");
        assertUnique("upds.csv", "number", "upd_date", "client_inn");
        assertUnique("payments.csv", "source_id");
        // номер сообщения сам по себе не уникален: одно сообщение даёт несколько строк событий
        assertUnique("events.csv", "ИНН", "Номер дела", "Номер сообщения", "Тип события", "Событие");
    }

    @Test
    void referencesResolve() {
        final Set<String> managers = column("managers.csv", "code");
        final Set<String> clients = column("clients.csv", "inn");
        final Set<String> contracts = read("contracts.csv").stream()
                .map(row -> row.get("client_inn") + "|" + row.get("buyer_inn") + "|" + row.get("number"))
                .collect(Collectors.toSet());

        assertThat(managers).containsAll(column("clients.csv", "manager_code"));
        assertThat(clients).containsAll(column("contracts.csv", "client_inn"))
                .containsAll(column("upds.csv", "client_inn"))
                .containsAll(column("payments.csv", "client_inn"));
        assertThat(read("upds.csv")).allSatisfy(upd -> assertThat(contracts)
                .contains(upd.get("client_inn") + "|" + upd.get("buyer_inn") + "|" + upd.get("contract_number")));
    }

    @Test
    void manifestDataDateIsLatestDocumentDate() throws IOException {
        final JsonNode manifest;
        try (InputStream in = resource("manifest.json")) {
            manifest = JsonMapper.builder().build().readTree(in);
        }
        final LocalDate latest = Stream.concat(
                        read("upds.csv").stream().map(row -> row.get("upd_date")),
                        read("payments.csv").stream().map(row -> row.get("payment_date")))
                .map(LocalDate::parse)
                .max(LocalDate::compareTo)
                .orElseThrow();

        assertThat(manifest.get("format_version").asString()).isEqualTo("1");
        assertThat(LocalDate.parse(manifest.get("data_date").asString())).isEqualTo(latest);
    }

    @Test
    void coversControlCases() {
        final Set<String> cases = Stream.of("clients.csv", "upds.csv", "payments.csv")
                .flatMap(file -> read(file).stream())
                .map(row -> row.get("control_case"))
                .filter(Objects::nonNull)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .collect(Collectors.toSet());

        // К-16 - сценарий повторного прогона после решения менеджера, в данных отдельной метки нет
        final List<String> expected = IntStream.rangeClosed(1, 18).filter(n -> n != 16).mapToObj(n -> "K-" + n).toList();
        assertThat(cases).containsAll(expected);
    }

    @Test
    void containsBuyerMissingFromRegistry() {
        // К-17: покупатель из УПД вне реестра - не ошибка формата
        final Set<String> missing = new HashSet<>(column("upds.csv", "buyer_inn"));
        missing.removeAll(column("buyers.csv", "inn"));

        assertThat(missing).containsExactly("7720000010");
    }

    private void assertUnique(final String file, final String... columns) {
        final List<String> keys = read(file).stream()
                .map(row -> Arrays.stream(columns).map(row::get).collect(Collectors.joining("|")))
                .toList();
        assertThat(keys).as(file).doesNotHaveDuplicates();
    }

    private Set<String> column(final String file, final String name) {
        return read(file).stream().map(row -> row.get(name)).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private List<Map<String, String>> read(final String file) {
        final String text;
        try (InputStream in = resource(file)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("﻿", "");
        } catch (IOException e) {
            throw new IllegalStateException(file, e);
        }
        final List<List<String>> records = parseCsv(text);
        final List<String> header = records.getFirst();
        return records.stream().skip(1).map(values -> {
            final Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                final String value = i < values.size() ? values.get(i) : "";
                row.put(header.get(i), value.isEmpty() ? null : value);
            }
            return row;
        }).toList();
    }

    private InputStream resource(final String file) {
        return Objects.requireNonNull(getClass().getResourceAsStream(DIR + file), file);
    }

    /**
     * Минимальный разбор CSV по RFC 4180 с разделителем ';' - только для проверки фикстуры.
     */
    private static List<List<String>> parseCsv(final String text) {
        final List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        final StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ';') {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                record.add(field.toString().replace("\r", ""));
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
