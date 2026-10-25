package ru.smart.factoring.backend.api.crm;

/**
 * Результат решения менеджера (блок 6).
 *
 * @param packageId id пакета в имитации ВТБ Факторинга для «Передать в факторинг», иначе null
 */
public record OfferActionResultDto(OfferDto offer, Long packageId) {
}
