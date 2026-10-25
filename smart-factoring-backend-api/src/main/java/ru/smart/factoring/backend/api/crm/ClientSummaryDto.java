package ru.smart.factoring.backend.api.crm;

import java.math.BigDecimal;

/**
 * Сводка по клиенту в карточке (п. 5.5).
 *
 * @param cashGap кассовый разрыв: просроченная дебиторка при низкой ликвидности клиента (Р-15), иначе null
 */
public record ClientSummaryDto(BigDecimal receivablesTotal, BigDecimal overdue, BigDecimal availableForFinancing, BigDecimal cashGap) {
}
