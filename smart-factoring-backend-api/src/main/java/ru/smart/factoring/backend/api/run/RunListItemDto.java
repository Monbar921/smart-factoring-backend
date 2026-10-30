package ru.smart.factoring.backend.api.run;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Строка истории прогонов (ЭП-8). initiatorRole - роли запустившего: operator, manager (П4-6).
 */
public record RunListItemDto(long id,
                             RunStatus status,
                             LocalDate calcDate,
                             OffsetDateTime startedAt,
                             OffsetDateTime finishedAt,
                             Long durationMs,
                             String initiator,
                             String initiatorRole,
                             Integer clientCount,
                             Integer openUpdCount,
                             Integer offerCount,
                             BigDecimal eadTotal) {
}
