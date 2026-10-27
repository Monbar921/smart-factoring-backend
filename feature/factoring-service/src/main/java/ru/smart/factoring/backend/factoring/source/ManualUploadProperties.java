package ru.smart.factoring.backend.factoring.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Настройки имитации источников.
 *
 * @param importDir каталог на сервере, из которого разрешён импорт данных ручной загрузки по пути (POST /manual-upload)
 */
@ConfigurationProperties("smart-factoring.manual-upload")
public record ManualUploadProperties(Path importDir) {
}
