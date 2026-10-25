package ru.smart.factoring.backend.api.details;

import java.math.BigDecimal;

/**
 * Клиент в детализации прогона (ЭП-5).
 */
public record RunClientDto(long clientId,
                           String inn,
                           String name,
                           String industry,
                           boolean core,
                           BigDecimal absLiquidity,
                           int openUpds,
                           BigDecimal openAmount,
                           int overdueUpds,
                           int offers,
                           BigDecimal eadTotal) {
}
