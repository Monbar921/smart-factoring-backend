package ru.smart.factoring.backend.factoring.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Разбор CSV формата демо-данных: UTF-8 (BOM допускается), разделитель ';', кавычки по RFC 4180, первая строка - заголовок.
 * Пустое значение - null.
 */
public final class Csv {
    private static final char SEPARATOR = ';';
    private static final char QUOTE = '"';

    private Csv() {
    }

    public static Table read(final Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Не прочитать " + file, e);
        }
    }

    public static Table parse(final String content) {
        final String text = content.startsWith("﻿") ? content.substring(1) : content;
        final List<List<String>> records = records(text);
        if (records.isEmpty()) {
            return new Table(List.of(), List.of());
        }
        final List<String> header = records.getFirst().stream().map(String::trim).toList();
        final List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            final List<String> values = records.get(i);
            if (values.size() == 1 && values.getFirst().isBlank()) {
                continue;
            }
            final Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                final String value = c < values.size() ? values.get(c) : null;
                row.put(header.get(c), value == null || value.isEmpty() ? null : value);
            }
            rows.add(new Row(i + 1, row));
        }
        return new Table(header, rows);
    }

    private static List<List<String>> records(final String text) {
        final List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        final StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quoted) {
                if (c == QUOTE && i + 1 < text.length() && text.charAt(i + 1) == QUOTE) {
                    field.append(QUOTE);
                    i++;
                } else if (c == QUOTE) {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == QUOTE) {
                quoted = true;
            } else if (c == SEPARATOR) {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                record.add(stripCr(field));
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(stripCr(field));
            records.add(record);
        }
        return records;
    }

    private static String stripCr(final StringBuilder field) {
        final int length = field.length();
        return length > 0 && field.charAt(length - 1) == '\r' ? field.substring(0, length - 1) : field.toString();
    }

    /**
     * Таблица CSV.
     */
    public record Table(List<String> header, List<Row> rows) {
    }

    /**
     * Строка CSV.
     *
     * @param line   номер строки в файле (заголовок - 1), для сообщений об ошибках
     * @param values значения по именам колонок
     */
    public record Row(int line, Map<String, String> values) {

        public String get(final String column) {
            return values.get(column);
        }
    }
}
