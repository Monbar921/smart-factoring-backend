package ru.smart.factoring.backend.api.crm;

/**
 * Пометки карточки предложения (п. 5.3, пункт 4).
 */
public record OfferFlagsDto(boolean delayProbable, boolean lowData, boolean reducedToLimit, boolean isNew) {
}
