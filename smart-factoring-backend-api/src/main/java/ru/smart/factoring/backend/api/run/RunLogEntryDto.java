package ru.smart.factoring.backend.api.run;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Запись журнала прогона (ЭП-6, НФР-7).
 */
public record RunLogEntryDto(long id,
                             OffsetDateTime loggedAt,
                             String level,
                             Integer stepNo,
                             String eventCode,
                             String message,
                             Long clientId,
                             Long buyerId,
                             Long updId,
                             Long offerId,
                             Map<String, Object> details) {
}
