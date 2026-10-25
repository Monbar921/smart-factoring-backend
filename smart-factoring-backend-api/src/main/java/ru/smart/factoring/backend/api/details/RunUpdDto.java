package ru.smart.factoring.backend.api.details;

import ru.smart.factoring.backend.api.common.ClientRefDto;
import ru.smart.factoring.backend.api.common.RiskLevel;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * УПД в детализации прогона (ЭП-5).
 *
 * @param stage ступень воронки, на которой УПД отсеян; QUEUED - дошёл до очереди; null - оплачен
 */
public record RunUpdDto(long updId,
                        String number,
                        LocalDate updDate,
                        ClientRefDto client,
                        ClientRefDto buyer,
                        BigDecimal amount,
                        BigDecimal outstanding,
                        String paymentStatus,
                        LocalDate plannedDueDate,
                        int daysToDue,
                        Integer category,
                        BigDecimal pd,
                        RiskLevel riskLevel,
                        BigDecimal pDelay,
                        BigDecimal pOverdue90,
                        String decision,
                        String reason,
                        String stage,
                        BigDecimal eadFinal,
                        BigDecimal incomeFinal) {
}
