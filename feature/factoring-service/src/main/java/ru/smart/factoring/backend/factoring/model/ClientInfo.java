package ru.smart.factoring.backend.factoring.model;

import java.math.BigDecimal;

/**
 * Клиент прогона: для запроса к модели задержки, TR (п. 5.3) и очереди менеджера.
 */
public record ClientInfo(long id, String inn, String name, String industry, BigDecimal absLiquidity, long managerId) {
}
