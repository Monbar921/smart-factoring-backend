package ru.smart.factoring.backend.api.details;

import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.api.crm.ReasonDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Покупатель в детализации прогона (ЭП-5).
 */
public record RunBuyerDto(long buyerId,
                          String inn,
                          String name,
                          String stopFactor,
                          boolean hasPaymentHistory,
                          BigDecimal pd,
                          String pdSource,
                          boolean lowData,
                          RiskLevel riskLevel,
                          List<ReasonDto> reasons,
                          String modelVersion,
                          LocalDate dataDate,
                          boolean fromCache,
                          int openUpds,
                          BigDecimal openAmount,
                          int offers,
                          BigDecimal eadTotal) {
}
