package ru.smart.factoring.backend.api.demo;

/**
 * Импорт демо-данных с диска сервера.
 *
 * @param path путь относительно каталога импорта (smart-factoring.demo.import-dir): папка выгрузки генератора
 *             или файл events.csv
 */
public record DemoImportRequest(String path) {
}
