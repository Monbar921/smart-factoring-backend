package ru.smart.factoring.backend.api.details;

import ru.smart.factoring.backend.api.run.RunLogEntryDto;

import java.util.List;

/**
 * Путь УПД через шаги прогона (ЭП-5).
 */
public record UpdTraceDto(RunUpdDto upd, List<TraceStepDto> steps, List<RunLogEntryDto> log) {
}
