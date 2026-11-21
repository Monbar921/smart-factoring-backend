package ru.smart.factoring.backend.factoring.db;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Список значений одним параметром-массивом PostgreSQL (Р-88): {@code x = any(cast(:ids as bigint[]))} вместо
 * {@code x in (:ids)}. Список Spring раскрывает в отдельные параметры, а у протокола PostgreSQL предел - 65 535
 * параметров на запрос: на объёме сегмента (сотни тысяч УПД) запрос с {@code in} падает.
 */
public final class SqlArrays {
    private SqlArrays() {
    }

    /**
     * Литерал массива чисел: {1,2,3}; пустой список - пустой массив (ни одна строка не подходит).
     */
    public static String ids(final Collection<? extends Number> values) {
        return values.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    /**
     * Литерал массива строк в кавычках: {"7701000001","7701000002"}.
     */
    public static String texts(final Collection<String> values) {
        return values.stream().filter(Objects::nonNull)
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }
}
