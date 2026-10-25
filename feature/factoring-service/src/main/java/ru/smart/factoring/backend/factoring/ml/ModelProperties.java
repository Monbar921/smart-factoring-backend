package ru.smart.factoring.backend.factoring.ml;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Настройки сервиса моделей (Р-35).
 *
 * @param mode         http - сервис smart-factoring-ml, stub - встроенная заглушка
 * @param baseUrl      адрес сервиса моделей для mode = http
 * @param timeout      таймаут ответа на пачку
 * @param batchSize    размер пачки покупателей или УПД в одном запросе (НФР-1.2)
 * @param stubScoresDir каталог с stub_buyer_scores.csv и stub_upd_scores.csv для mode = stub (необязательно)
 */
@ConfigurationProperties("smart-factoring.ml")
public record ModelProperties(String mode, String baseUrl, Duration timeout, Integer batchSize, Path stubScoresDir) {
    private static final int DEFAULT_BATCH = 200;

    public int batch() {
        return batchSize != null && batchSize > 0 ? batchSize : DEFAULT_BATCH;
    }
}
