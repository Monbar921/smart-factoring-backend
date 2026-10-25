package ru.smart.factoring.backend.api.details;

import java.math.BigDecimal;

/**
 * Отсев на ступени воронки по причине.
 */
public record FunnelDropDto(String reason, int count, BigDecimal amount) {
}
