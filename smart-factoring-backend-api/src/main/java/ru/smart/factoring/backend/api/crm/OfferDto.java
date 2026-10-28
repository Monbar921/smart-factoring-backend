package ru.smart.factoring.backend.api.crm;

import ru.smart.factoring.backend.api.common.ClientRefDto;
import ru.smart.factoring.backend.api.common.RiskLevel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Карточка предложения для менеджера (п. 5.3, пункт 4).
 *
 * @param status     QUEUED, TRANSFERRED, REJECTED или REVOKED
 * @param textSource откуда тексты карточки: LLM - от LLM в этом прогоне, CACHE - ответ LLM из кеша, TEMPLATE - шаблон
 *                   без LLM, FAILED - LLM не ответила (причины шаблоном, без черновика); null - предложение до Р-58
 * @param pDelay     вероятность задержки оплаты больше порога по модели задержки (последний прогон); null - модель
 *                   задержки не применялась (нет истории оплат)
 * @param delayThresholdDays порог задержки прогона, дней (7, 14 или 30): «оплатят позже срока больше чем на X дней»
 */
public record OfferDto(long offerId,
                       String status,
                       long updId,
                       String updNumber,
                       LocalDate updDate,
                       BigDecimal updOutstanding,
                       ClientRefDto client,
                       ClientRefDto buyer,
                       BigDecimal ead,
                       BigDecimal price,
                       BigDecimal priceRate,
                       int termDays,
                       RiskLevel riskLevel,
                       BigDecimal pd,
                       BigDecimal income,
                       BigDecimal el,
                       BigDecimal tr,
                       BigDecimal rank,
                       OfferFlagsDto flags,
                       List<ReasonDto> reasons,
                       String reasonsText,
                       String messageDraft,
                       OffsetDateTime queuedAt,
                       long lastRunId,
                       String revokeReason,
                       String decidedBy,
                       OffsetDateTime decidedAt,
                       String textSource,
                       BigDecimal pDelay,
                       Integer delayThresholdDays) {
}
