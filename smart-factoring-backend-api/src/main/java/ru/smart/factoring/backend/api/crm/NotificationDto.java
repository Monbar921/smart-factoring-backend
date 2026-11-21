package ru.smart.factoring.backend.api.crm;

import ru.smart.factoring.backend.api.common.ClientRefDto;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Уведомление менеджеру (п. 3.10, 5.3, 5.4).
 *
 * @param runId прогон; null - уведомление вне прогона: событие ЕФРСБ по покупателю (Р-86)
 * @param type  NEW_OFFER, OFFER_CHANGED, OFFER_REVOKED, OVERDUE, REMINDER или RISK_SIGNAL
 */
public record NotificationDto(long id, Long runId, String type, OffsetDateTime createdAt, ClientRefDto client, ClientRefDto buyer,
                              Long updId, String updNumber, Long offerId, Map<String, Object> payload) {
}
