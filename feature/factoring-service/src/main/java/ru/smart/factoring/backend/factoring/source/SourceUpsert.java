package ru.smart.factoring.backend.factoring.source;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ru.smart.factoring.backend.api.manualupload.ManualUploadTableStatsDto;
import ru.smart.factoring.backend.factoring.error.ApiException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Запись одной таблицы источника при импорте-дельте (Р-52): новые ключи добавляются, существующие обновляются,
 * ничего не удаляется.
 * <ol>
 *     <li>строки файла - во временную таблицу stg_&lt;таблица&gt; (до конца транзакции);</li>
 *     <li>тот же ключ, но другое неизменяемое поле (у УПД - покупатель, у платежа - сумма) - конфликт:
 *     строка пропускается, в таблице остаются прежние данные;</li>
 *     <li>изменения изменяемых полей - в отчёт (для документов);</li>
 *     <li>upsert одним запросом: запись без изменений не трогается и считается «без изменений».</li>
 * </ol>
 *
 * @param name         имя таблицы в отчёте импорта (upds, payments...)
 * @param table        таблица источника
 * @param file         файл выгрузки - для отчёта
 * @param keys         естественный ключ
 * @param nullableKeys ключевые колонки, которые бывают пустыми (сравниваются через coalesce, как в уникальном индексе)
 * @param immutable    поля, которые у записи с этим ключом меняться не могут
 * @param mutable      поля, которые новая выгрузка может изменить
 * @param reportChanges изменения записей - в отчёт (документы: договоры, УПД, платежи; не справочники)
 * @param duplicateBy  поля, совпадение которых у записей с разными ключами - «возможный дубль» (загружается, в отчёт)
 */
record SourceUpsert(String name, String table, String file, List<String> keys, Set<String> nullableKeys, List<String> immutable,
                    List<String> mutable, boolean reportChanges, List<String> duplicateBy) {

    List<String> columns() {
        return Stream.of(keys, immutable, mutable).flatMap(List::stream).toList();
    }

    ManualUploadTableStatsDto write(final NamedParameterJdbcTemplate jdbc, final List<Row> rows, final ImportReport report) {
        if (rows.isEmpty()) {
            return null;
        }
        final String stage = "stg_" + table;
        final String columns = String.join(", ", columns());
        // в наборе партий таблица пишется несколько раз за транзакцию
        jdbc.getJdbcTemplate().execute("drop table if exists " + stage);
        jdbc.getJdbcTemplate().execute("create temp table " + stage + " on commit drop as select " + columns + " from " + table
                + " where false");
        jdbc.getJdbcTemplate().execute("alter table " + stage + " add column line integer");
        execute(() -> jdbc.batchUpdate("insert into " + stage + " (" + columns + ", line) values ("
                        + columns().stream().map(column -> ":" + column).collect(Collectors.joining(", ")) + ", :line)",
                rows.stream().map(row -> row.values().addValue("line", row.line())).toArray(MapSqlParameterSource[]::new)));
        // у временной таблицы нет статистики: без неё планировщик считает её пустой и сравнивает строки перебором
        jdbc.getJdbcTemplate().execute("analyze " + stage);

        final int conflicts = conflicts(jdbc, stage, report);
        if (reportChanges) {
            changes(jdbc, stage, report);
        }
        if (!duplicateBy.isEmpty()) {
            duplicates(jdbc, stage, report);
        }
        final List<Boolean> written = execute(() -> jdbc.getJdbcTemplate().queryForList("insert into " + table + " (" + columns
                + ") select " + columns + " from " + stage + " on conflict (" + conflictTarget() + ") do update set "
                + mutable.stream().map(column -> column + " = excluded." + column).collect(Collectors.joining(", "))
                + " where " + distinct(table, "excluded", mutable) + " returning (xmax = 0)", Boolean.class));
        final int inserted = (int) written.stream().filter(Boolean.TRUE::equals).count();
        final int updated = written.size() - inserted;
        final ManualUploadTableStatsDto stats = new ManualUploadTableStatsDto(rows.size(), inserted, updated,
                rows.size() - conflicts - written.size(), conflicts);
        report.table(name, stats);
        return stats;
    }

    /**
     * Конфликты: строки удаляются из временной таблицы и попадают в отчёт с прежним и новым значением.
     */
    private int conflicts(final NamedParameterJdbcTemplate jdbc, final String stage, final ImportReport report) {
        if (immutable.isEmpty()) {
            return 0;
        }
        final String condition = join("d", "s") + " and " + distinct("d", "s", immutable);
        final List<Map<String, Object>> found = jdbc.getJdbcTemplate().queryForList("select s.line, "
                + keys.stream().map(key -> "s." + key + " as k_" + key).collect(Collectors.joining(", ")) + ", "
                + immutable.stream().map(column -> "d." + column + " as o_" + column + ", s." + column + " as n_" + column)
                .collect(Collectors.joining(", "))
                + " from " + stage + " s join " + table + " d on " + condition + " order by s.line");
        for (final Map<String, Object> row : found) {
            report.issue(ImportReport.Level.CONFLICT, file, (Integer) row.get("line"), key(row),
                    "уже загружено с другими значениями: " + difference(row, immutable) + " - строка пропущена");
        }
        if (!found.isEmpty()) {
            jdbc.getJdbcTemplate().update("delete from " + stage + " s using " + table + " d where " + condition);
        }
        return found.size();
    }

    private void changes(final NamedParameterJdbcTemplate jdbc, final String stage, final ImportReport report) {
        final List<String> reported = mutable.stream().filter(column -> !"control_case".equals(column)).toList();
        if (reported.isEmpty()) {
            return;
        }
        final List<Map<String, Object>> found = jdbc.getJdbcTemplate().queryForList("select s.line, "
                + keys.stream().map(key -> "s." + key + " as k_" + key).collect(Collectors.joining(", ")) + ", "
                + reported.stream().map(column -> "d." + column + " as o_" + column + ", s." + column + " as n_" + column)
                .collect(Collectors.joining(", "))
                + " from " + stage + " s join " + table + " d on " + join("d", "s") + " and " + distinct("d", "s", reported)
                + " order by s.line");
        for (final Map<String, Object> row : found) {
            report.issue(ImportReport.Level.CHANGED, file, (Integer) row.get("line"), key(row), "изменено: " + difference(row, reported));
        }
    }

    /**
     * Возможные дубли новых записей (ключа ещё нет в таблице): то же содержание у уже загруженной записи
     * или у другой новой строки файла с другим ключом. Пара в отчёте один раз.
     */
    private void duplicates(final NamedParameterJdbcTemplate jdbc, final String stage, final ImportReport report) {
        final String content = Stream.concat(keys.stream(), duplicateBy.stream()).distinct().collect(Collectors.joining(", "));
        final List<Map<String, Object>> found = jdbc.getJdbcTemplate().queryForList("with fresh as (select * from " + stage
                + " s where not exists (select 1 from " + table + " e where " + join("e", "s") + ")), "
                + "other as (select " + content + " from " + table + " union all select " + content + " from fresh) "
                + "select f.line, " + keys.stream().map(key -> "f." + key + " as k_" + key + ", o." + key + " as d_" + key)
                .collect(Collectors.joining(", "))
                + " from fresh f join other o on " + contentRow("o") + " = " + contentRow("f")
                + " and row(" + keys.stream().map(key -> "o." + key).collect(Collectors.joining(", ")) + ") is distinct from row("
                + keys.stream().map(key -> "f." + key).collect(Collectors.joining(", ")) + ") order by f.line");
        final Set<String> pairs = new HashSet<>();
        for (final Map<String, Object> row : found) {
            final String self = key(row);
            final String other = keys.stream().map(key -> row.get("d_" + key)).filter(Objects::nonNull).map(Object::toString)
                    .collect(Collectors.joining(" / "));
            if (pairs.add(self.compareTo(other) < 0 ? self + "|" + other : other + "|" + self)) {
                report.issue(ImportReport.Level.DUPLICATE, file, (Integer) row.get("line"), self,
                        "возможный дубль " + other + ": совпадают " + String.join(", ", duplicateBy) + " - загружено, проверьте");
            }
        }
    }

    /**
     * Содержание записи одной строкой: сравнение «=» по ней хешируется (с «is not distinct from» по колонкам - перебор
     * всех пар), null совпадает с null. Типы колонок у таблицы и временной таблицы одни, поэтому 1500000.00 = 1500000.00.
     */
    private String contentRow(final String alias) {
        return "row(" + duplicateBy.stream().map(column -> alias + "." + column).collect(Collectors.joining(", ")) + ")::text";
    }

    /**
     * Цель on conflict - как в уникальном индексе таблицы.
     */
    private String conflictTarget() {
        return keys.stream().map(key -> nullableKeys.contains(key) ? "coalesce(" + key + ", '')" : key).collect(Collectors.joining(", "));
    }

    private String join(final String left, final String right) {
        return keys.stream()
                .map(key -> nullableKeys.contains(key)
                        ? "coalesce(" + left + "." + key + ", '') = coalesce(" + right + "." + key + ", '')"
                        : left + "." + key + " = " + right + "." + key)
                .collect(Collectors.joining(" and "));
    }

    private static String distinct(final String left, final String right, final List<String> columns) {
        return "row(" + columns.stream().map(column -> left + "." + column).collect(Collectors.joining(", ")) + ") is distinct from row("
                + columns.stream().map(column -> right + "." + column).collect(Collectors.joining(", ")) + ")";
    }

    private String key(final Map<String, Object> row) {
        return keys.stream().map(key -> row.get("k_" + key)).filter(Objects::nonNull).map(Object::toString)
                .collect(Collectors.joining(" / "));
    }

    private static String difference(final Map<String, Object> row, final List<String> columns) {
        final List<String> changed = new ArrayList<>();
        for (final String column : columns) {
            final Object before = row.get("o_" + column);
            final Object after = row.get("n_" + column);
            if (!Objects.equals(normalize(before), normalize(after))) {
                changed.add(column + " " + before + " → " + after);
            }
        }
        return String.join(", ", changed);
    }

    /**
     * 1500000.00 и 1500000 - одно значение.
     */
    private static Object normalize(final Object value) {
        return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros() : value;
    }

    private static <T> T execute(final Supplier<T> write) {
        try {
            return write.get();
        } catch (DataAccessException e) {
            // ограничения БД, которые не проверил валидатор (например, длина значения): отказ всей выгрузки
            throw new ApiException("IMPORT_INVALID", HttpStatus.BAD_REQUEST, "Выгрузка не записалась в БД",
                    Map.of("errors", List.of(String.valueOf(e.getMostSpecificCause().getMessage()))));
        }
    }

    /**
     * Строка файла: значения колонок и номер строки для отчёта.
     */
    record Row(int line, MapSqlParameterSource values) {
    }
}
