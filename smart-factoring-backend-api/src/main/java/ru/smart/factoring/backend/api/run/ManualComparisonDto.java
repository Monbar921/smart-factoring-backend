package ru.smart.factoring.backend.api.run;

/**
 * Сравнение с ручной работой (ЭП-9): расчётное время ручной проработки тех же клиентов и время прогона.
 */
public record ManualComparisonDto(int clients, long manualMinutes, long systemMinutes, Long runDurationMs) {
}
