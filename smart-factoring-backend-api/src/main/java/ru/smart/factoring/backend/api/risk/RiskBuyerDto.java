package ru.smart.factoring.backend.api.risk;

import java.math.BigDecimal;

/**
 * Покупатель с действующими предложениями - кому можно «прислать» событие ЕФРСБ в демо (Р-86).
 *
 * @param queuedOffers      предложений в очереди менеджеров
 * @param queuedEad         их сумма к финансированию
 * @param transferredOffers переданных в факторинг
 */
public record RiskBuyerDto(String inn, String name, int queuedOffers, BigDecimal queuedEad, int transferredOffers) {
}
