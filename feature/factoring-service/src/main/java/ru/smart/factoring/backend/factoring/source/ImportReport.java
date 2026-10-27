package ru.smart.factoring.backend.factoring.source;

import ru.smart.factoring.backend.api.demo.DemoImportIssueDto;
import ru.smart.factoring.backend.api.demo.DemoTableStatsDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Отчёт импорта-дельты (Р-52): по таблицам - добавлено / изменено / без изменений / пропущено,
 * и замечания по строкам: конфликт (строка пропущена), изменение, возможный дубль, предупреждение.
 */
final class ImportReport {
    /** в ответе и в demo_import_batch - не больше стольких замечаний, счётчики по уровням - полные */
    private static final int MAX_ISSUES = 500;

    enum Level {
        /** тот же ключ, но изменилось неизменяемое поле - строка пропущена, в БД остались прежние данные */
        CONFLICT,
        /** строка обновила существующую запись */
        CHANGED,
        /** запись с другим ключом и тем же содержанием - загружена, проверить вручную */
        DUPLICATE,
        /** загружено, но требует внимания (например, изменился УПД, переданный в факторинг) */
        WARNING
    }

    private final Map<String, DemoTableStatsDto> tables = new LinkedHashMap<>();
    private final List<DemoImportIssueDto> issues = new ArrayList<>();
    private final Map<String, Integer> issueCounts = new TreeMap<>();

    void table(final String name, final DemoTableStatsDto stats) {
        tables.put(name, stats);
    }

    void issue(final Level level, final String file, final Integer line, final String key, final String message) {
        issueCounts.merge(level.name(), 1, Integer::sum);
        if (issues.size() < MAX_ISSUES) {
            issues.add(new DemoImportIssueDto(level.name(), file, line, key, message));
        }
    }

    int count(final Level level) {
        return issueCounts.getOrDefault(level.name(), 0);
    }

    Map<String, DemoTableStatsDto> tables() {
        return tables;
    }

    List<DemoImportIssueDto> issues() {
        return issues;
    }

    Map<String, Integer> issueCounts() {
        return issueCounts;
    }
}
