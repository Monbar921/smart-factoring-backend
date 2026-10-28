package ru.smart.factoring.backend.api.run;

import java.util.List;

/**
 * Партия ручной загрузки (Р-62): её клиенты, доступные для прогона - выбор клиентов прогона по партиям.
 *
 * @param batchId    batch_id партии ML-команды (batch_01…)
 * @param clientInns ИНН клиентов партии
 */
public record RunBatchDto(String batchId, List<String> clientInns) {
}
