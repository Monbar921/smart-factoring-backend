package ru.smart.factoring.backend.api.details;

import java.util.Map;

/**
 * Что получилось с УПД на шаге прогона (ЭП-5).
 */
public record TraceStepDto(int stepNo, String name, String summary, Map<String, Object> details) {
}
