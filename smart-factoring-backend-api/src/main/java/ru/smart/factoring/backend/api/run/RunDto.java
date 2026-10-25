package ru.smart.factoring.backend.api.run;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Прогон со статусами и результатами шагов (ЭП-2, ЭП-3, ЭП-9).
 */
public record RunDto(long id,
                     RunStatus status,
                     LocalDate calcDate,
                     List<String> clientInns,
                     InitiatorType initiatorType,
                     String initiator,
                     OffsetDateTime startedAt,
                     OffsetDateTime finishedAt,
                     Long durationMs,
                     LocalDate dataDate,
                     LocalDate snapshotDate,
                     Map<String, Object> modelVersions,
                     String errorMessage,
                     List<RunStepDto> steps,
                     ManualComparisonDto comparison) {
}
