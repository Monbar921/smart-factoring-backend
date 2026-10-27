package ru.smart.factoring.backend.api.run;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Шаг прогона: статус, время, числа результата (ЭП-2, ЭП-3) и прогресс долгой части шага (null - у шага её нет).
 */
public record RunStepDto(int stepNo,
                         String name,
                         StepStatus status,
                         OffsetDateTime startedAt,
                         OffsetDateTime finishedAt,
                         Long durationMs,
                         Map<String, Object> metrics,
                         RunStepProgressDto progress) {
}
