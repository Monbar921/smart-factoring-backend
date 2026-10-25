package ru.smart.factoring.backend.api.demo;

import java.time.LocalDate;
import java.util.Map;

/**
 * Результат импорта: номер импорта, даты данных и снимка, число строк по таблицам.
 */
public record DemoImportResultDto(long batchId, LocalDate dataDate, LocalDate snapshotDate, Map<String, Integer> rowCounts) {
}
