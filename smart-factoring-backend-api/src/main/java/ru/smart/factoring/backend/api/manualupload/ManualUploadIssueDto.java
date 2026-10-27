package ru.smart.factoring.backend.api.manualupload;

/**
 * Замечание импорта по строке.
 *
 * @param level   CONFLICT - строка пропущена, CHANGED - запись обновлена, DUPLICATE - возможный дубль (загружен),
 *                WARNING - загружено, но требует внимания
 * @param file    файл выгрузки
 * @param line    строка файла, null - замечание не к строке
 * @param key     ключ записи, например номер УПД или source_id платежа
 * @param message что не так, по-русски
 */
public record ManualUploadIssueDto(String level, String file, Integer line, String key, String message) {
}
