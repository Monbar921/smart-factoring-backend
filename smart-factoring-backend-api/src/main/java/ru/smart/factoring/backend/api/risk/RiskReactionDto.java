package ru.smart.factoring.backend.api.risk;

import ru.smart.factoring.backend.api.common.ClientRefDto;

import java.math.BigDecimal;

/**
 * Реакция на событие по покупателю (Р-86).
 *
 * @param buyer             покупатель
 * @param event             событие по-русски
 * @param stopFactor        BANKRUPTCY, LIQUIDATION, EXCLUSION; null - событие не стоп-фактор, предложения остаются
 * @param revokedOffers     отозвано предложений из очереди менеджеров
 * @param revokedEad        их сумма к финансированию
 * @param transferredOffers уже переданных в факторинг предложений по покупателю - о них узнаёт риск-подразделение
 * @param notifiedManagers  менеджеров, получивших уведомление
 * @param riskSignal        true - отправлен сигнал в риск-подразделение
 * @param message           итог одной строкой для оператора
 */
public record RiskReactionDto(ClientRefDto buyer, String event, String stopFactor, int revokedOffers, BigDecimal revokedEad,
                              int transferredOffers, int notifiedManagers, boolean riskSignal, String message) {
}
