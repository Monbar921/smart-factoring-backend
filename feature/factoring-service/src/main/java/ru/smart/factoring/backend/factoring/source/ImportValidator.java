package ru.smart.factoring.backend.factoring.source;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Проверка значений выгрузки (docs/claude/module-0/demo-dataset-format.md): копит ошибки «файл:строка: что не так».
 */
final class ImportValidator {
    private static final int MAX_ERRORS = 200;

    private final List<String> errors = new ArrayList<>();
    /** папка партии в наборе (batch_01/) - перед именем файла в ошибках */
    private String prefix = "";

    void prefix(final String folder) {
        prefix = folder;
    }

    List<String> errors() {
        return errors;
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    void error(final String file, final Integer line, final String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(line != null ? prefix + file + ":" + line + ": " + message : prefix + file + ": " + message);
        }
    }

    boolean columns(final String file, final Csv.Table table, final Collection<String> required) {
        final Set<String> missing = new HashSet<>(required);
        missing.removeAll(table.header());
        if (!missing.isEmpty()) {
            error(file, null, "нет колонок " + missing);
            return false;
        }
        return true;
    }

    String required(final String file, final Csv.Row row, final String column) {
        final String value = row.get(column);
        if (value == null || value.isBlank()) {
            error(file, row.line(), "пусто в обязательной колонке " + column);
            return null;
        }
        return value.trim();
    }

    LocalDate date(final String file, final Csv.Row row, final String column, final boolean mandatory) {
        final String value = mandatory ? required(file, row, column) : row.get(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            error(file, row.line(), column + ": не дата YYYY-MM-DD: " + value);
            return null;
        }
    }

    BigDecimal decimal(final String file, final Csv.Row row, final String column, final boolean mandatory) {
        final String value = mandatory ? required(file, row, column) : row.get(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            error(file, row.line(), column + ": не число: " + value);
            return null;
        }
    }

    Integer integer(final String file, final Csv.Row row, final String column, final boolean mandatory) {
        final BigDecimal value = decimal(file, row, column, mandatory);
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            error(file, row.line(), column + ": не целое: " + value);
            return null;
        }
    }

    Boolean bool(final String file, final Csv.Row row, final String column, final boolean mandatory) {
        final String value = mandatory ? required(file, row, column) : row.get(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase()) {
            case "true" -> true;
            case "false" -> false;
            default -> {
                error(file, row.line(), column + ": ожидается true или false: " + value);
                yield null;
            }
        };
    }

    void unique(final String file, final Csv.Row row, final Set<String> seen, final String key) {
        if (!seen.add(key)) {
            error(file, row.line(), "повтор ключа " + key);
        }
    }
}
