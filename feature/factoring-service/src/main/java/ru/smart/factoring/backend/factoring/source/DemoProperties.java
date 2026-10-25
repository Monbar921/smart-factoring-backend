package ru.smart.factoring.backend.factoring.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Настройки имитации источников.
 *
 * @param importDir каталог на сервере, из которого разрешён импорт демо-данных по пути (POST /demo/import)
 */
@ConfigurationProperties("smart-factoring.demo")
public record DemoProperties(Path importDir) {
}
