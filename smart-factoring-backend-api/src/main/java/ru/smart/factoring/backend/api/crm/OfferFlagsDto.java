package ru.smart.factoring.backend.api.crm;

/**
 * Пометки карточки предложения (п. 5.3, пункт 4).
 */
/**
 * @param lowDataReason причина «мало данных» (NO_RECENT_REPORT, LOW_REVENUE, YOUNG_COMPANY, EXCLUDED_INDUSTRY) по оценке
 *                      последнего прогона; null - данных хватает или причина неизвестна
 */
public record OfferFlagsDto(boolean delayProbable, boolean lowData, boolean reducedToLimit, boolean isNew, String lowDataReason) {
}
