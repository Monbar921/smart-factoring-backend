package ru.smart.factoring.backend.api.details;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ступень воронки УПД (ЭП-4): сколько УПД и на какую сумму дошло до ступени, кто отсеян и почему.
 */
public record FunnelStageDto(String code, String title, int count, BigDecimal amount, List<FunnelDropDto> dropped) {
}
