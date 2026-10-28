package ru.smart.factoring.backend.factoring.details;

import java.util.List;

/**
 * Ступени воронки УПД (ЭП-4) и причины отсева на каждой. LIMIT_EXHAUSTED и INCOME_BELOW_MIN бывают и до, и после
 * деления лимита: после деления (есть limit_order) - отсев на ступени QUEUED.
 */
public enum FunnelStage {
    OPEN("Все неоплаченные счета", List.of()),
    CATEGORY_1_4("Срок оплаты ещё не прошёл", List.of("UPD_OVERDUE", "ALREADY_DECIDED")),
    HAS_DATA("По покупателю есть данные", List.of("NO_BUYER_DATA")),
    NO_STOP_FACTOR("У покупателя нет стоп-факторов", List.of("STOP_FACTOR")),
    HAS_HISTORY("Есть история оплат", List.of("NO_PAYMENT_HISTORY", "SCORING_ERROR")),
    PASSED_RULES("Подходит по условиям банка", List.of("RISK_LEVEL_D", "OVERDUE_90_PROBABLE", "LIMIT_EXHAUSTED", "DELAY_PROBABLE_LEVEL_C",
            "SMALL_AMOUNT", "SHORT_TERM", "INCOME_BELOW_MIN")),
    TOP_N("Три лучших предложения на клиента", List.of("NOT_IN_TOP_N")),
    QUEUED("Можно предложить клиентам", List.of("LIMIT_EXHAUSTED", "INCOME_BELOW_MIN"));

    private final String title;
    private final List<String> reasons;

    FunnelStage(final String title, final List<String> reasons) {
        this.title = title;
        this.reasons = reasons;
    }

    public String title() {
        return title;
    }

    /**
     * Ступень, на которой отсеян УПД с этой причиной; null - дошёл до очереди.
     */
    public static FunnelStage droppedAt(final String reason, final boolean afterLimitDivision) {
        if (reason == null) {
            return null;
        }
        if (afterLimitDivision) {
            return QUEUED;
        }
        for (final FunnelStage stage : values()) {
            if (stage != QUEUED && stage.reasons.contains(reason)) {
                return stage;
            }
        }
        return PASSED_RULES;
    }
}
