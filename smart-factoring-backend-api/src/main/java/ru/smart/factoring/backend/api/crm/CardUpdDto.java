package ru.smart.factoring.backend.api.crm;

import ru.smart.factoring.backend.api.common.ClientRefDto;
import ru.smart.factoring.backend.api.common.RiskLevel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Открытый УПД в карточке клиента с оценкой и решением (п. 5.5).
 *
 * @param noOfferReason код причины (п. 5.2, 5.3) или null, если есть предложение
 * @param offerStatus   статус последнего предложения по УПД: QUEUED, TRANSFERRED, REJECTED; null - предложения не было
 */
public record CardUpdDto(long updId,
                         String number,
                         LocalDate updDate,
                         ClientRefDto buyer,
                         BigDecimal amount,
                         BigDecimal outstanding,
                         LocalDate plannedDueDate,
                         int daysToDue,
                         Integer category,
                         BigDecimal pd,
                         RiskLevel riskLevel,
                         BigDecimal pDelay,
                         BigDecimal pOverdue90,
                         BigDecimal expectedDelayDays,
                         List<ReasonDto> reasons,
                         Long offerId,
                         String noOfferReason,
                         String offerStatus) {
}
