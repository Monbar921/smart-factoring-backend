package ru.smart.factoring.backend.api.crm;

import ru.smart.factoring.backend.api.common.ClientRefDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Карточка клиента для менеджера (п. 5.5): по последнему успешному прогону, в котором был клиент.
 *
 * @param offers        предложения в очереди: сначала новые в прогоне, затем по рангу
 * @param decidedOffers переданные в факторинг и отклонённые предложения
 */
public record ClientCardDto(ClientRefDto client,
                            BigDecimal absLiquidity,
                            Long runId,
                            LocalDate calcDate,
                            LocalDate dataDate,
                            LocalDate snapshotDate,
                            ClientSummaryDto summary,
                            List<OfferDto> offers,
                            List<OfferDto> decidedOffers,
                            List<CardUpdDto> upds) {
}
