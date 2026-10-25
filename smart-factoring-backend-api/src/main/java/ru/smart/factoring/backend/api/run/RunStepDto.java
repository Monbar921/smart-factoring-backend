package ru.smart.factoring.backend.api.run;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Шаг прогона: статус, время и числа результата (ЭП-2, ЭП-3).
 */
public record RunStepDto(int stepNo,
                         String name,
                         StepStatus status,
                         OffsetDateTime startedAt,
                         OffsetDateTime finishedAt,
                         Long durationMs,
                         Map<String, Object> metrics) {
}
