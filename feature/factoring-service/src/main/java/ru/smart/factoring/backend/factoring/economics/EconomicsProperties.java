package ru.smart.factoring.backend.factoring.economics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Допущения экономического эффекта (Р-76). Обоснование чисел - docs/economics.md и финансовая модель (xlsx).
 *
 * @param coreClients         «ядро» сегмента: компании с выручкой 0,8–2 млрд ₽, у которых деньги застряли у покупателей
 *                            (RFSD, BRD «Проблема клиента»)
 * @param segmentClients      весь сегмент: компании с выручкой 0,8–2 млрд ₽ и полной отчётностью
 * @param implementationCost  внедрение, ₽ разово
 * @param fixedAnnualCost     сопровождение, ₽ в год: команда и инфраструктура, не зависящие от числа клиентов
 * @param annualCostPerClient сопровождение на клиента, ₽ в год: вычисления, хранение, LLM
 * @param reviewsPerYear      сколько раз в год менеджер прорабатывает клиента
 * @param managerHourCost     час менеджера с начислениями, ₽
 * @param fteHoursPerYear     часов в году на одну ставку
 */
@ConfigurationProperties("smart-factoring.economics")
public record EconomicsProperties(Integer coreClients, Integer segmentClients, BigDecimal implementationCost,
                                  BigDecimal fixedAnnualCost, BigDecimal annualCostPerClient, Integer reviewsPerYear,
                                  BigDecimal managerHourCost, Integer fteHoursPerYear) {
}
