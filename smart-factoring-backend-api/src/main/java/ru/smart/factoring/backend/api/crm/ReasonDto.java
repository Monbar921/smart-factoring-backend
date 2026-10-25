package ru.smart.factoring.backend.api.crm;

import java.math.BigDecimal;

/**
 * Причина оценки покупателя с направлением влияния (п. 4.4).
 *
 * @param direction INCREASES_RISK или DECREASES_RISK
 */
public record ReasonDto(String feature, String title, BigDecimal value, String direction, BigDecimal contribution) {
}
