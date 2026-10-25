package ru.smart.factoring.backend.api.run;

/**
 * Статус шага прогона (ЭП-2).
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    DONE,
    WARNING,
    STOPPED
}
