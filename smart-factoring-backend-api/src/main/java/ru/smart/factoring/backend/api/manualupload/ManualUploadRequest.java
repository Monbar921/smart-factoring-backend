package ru.smart.factoring.backend.api.manualupload;

/**
 * Импорт данных ручной загрузки с диска сервера.
 *
 * @param path путь относительно каталога импорта (smart-factoring.manual-upload.import-dir): папка выгрузки генератора
 *             или файл events.csv
 */
public record ManualUploadRequest(String path) {
}
