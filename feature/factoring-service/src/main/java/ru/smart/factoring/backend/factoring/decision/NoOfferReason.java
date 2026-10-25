package ru.smart.factoring.backend.factoring.decision;

/**
 * Причина отсутствия предложения - первое сработавшее правило (п. 5.2, 5.3; коды - data-model.md).
 */
public enum NoOfferReason {
    UPD_PAID,
    UPD_OVERDUE,
    ALREADY_DECIDED,
    STOP_FACTOR,
    NO_BUYER_DATA,
    NO_PAYMENT_HISTORY,
    SCORING_ERROR,
    RISK_LEVEL_D,
    OVERDUE_90_PROBABLE,
    LIMIT_EXHAUSTED,
    DELAY_PROBABLE_LEVEL_C,
    SMALL_AMOUNT,
    SHORT_TERM,
    INCOME_BELOW_MIN,
    NOT_IN_TOP_N
}
