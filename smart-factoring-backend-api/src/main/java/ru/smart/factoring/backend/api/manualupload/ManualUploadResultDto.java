package ru.smart.factoring.backend.api.manualupload;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Результат импорта-дельты (Р-52).
 *
 * @param batchId      номер импорта
 * @param dataDate     последняя дата УПД и платежей по всем загруженным данным (дата расчёта по умолчанию)
 * @param snapshotDate дата снимка внешних данных
 * @param rowCounts    строк в файлах по таблицам
 * @param tables       добавлено / изменено / без изменений / пропущено по таблицам
 * @param issueCounts  число замечаний по уровням (CONFLICT, CHANGED, DUPLICATE, WARNING)
 * @param issues       замечания по строкам (не больше 500)
 */
public record ManualUploadResultDto(long batchId, LocalDate dataDate, LocalDate snapshotDate, Map<String, Integer> rowCounts,
                                  Map<String, ManualUploadTableStatsDto> tables, Map<String, Integer> issueCounts,
                                  List<ManualUploadIssueDto> issues) {
}
