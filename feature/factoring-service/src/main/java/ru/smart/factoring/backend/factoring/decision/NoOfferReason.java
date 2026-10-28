package ru.smart.factoring.backend.factoring.decision;

/**
 * Причина отсутствия предложения - первое сработавшее правило (п. 5.2, 5.3; коды - data-model.md).
 */
public enum NoOfferReason {
    UPD_PAID("УПД оплачен"),
    UPD_OVERDUE("УПД просрочен"),
    ALREADY_DECIDED("уже есть решение менеджера"),
    STOP_FACTOR("стоп-фактор"),
    NO_BUYER_DATA("нет данных по покупателю"),
    NO_PAYMENT_HISTORY("нет истории оплат"),
    SCORING_ERROR("ошибка оценки"),
    RISK_LEVEL_D("уровень риска D"),
    OVERDUE_90_PROBABLE("вероятна просрочка 90+"),
    LIMIT_EXHAUSTED("лимит исчерпан"),
    DELAY_PROBABLE_LEVEL_C("вероятна задержка при уровне C"),
    SMALL_AMOUNT("малый остаток УПД"),
    SHORT_TERM("мало дней до оплаты"),
    INCOME_BELOW_MIN("доход ниже минимума"),
    NOT_IN_TOP_N("не в трёх лучших по клиенту");

    private final String title;

    NoOfferReason(final String title) {
        this.title = title;
    }

    /**
     * Причина по-русски, со строчной буквы - для фраз «Нет предложения: …» (путь УПД).
     */
    public String title() {
        return title;
    }

    /**
     * Название причины по коду из БД; неизвестный код - как есть.
     */
    public static String titleOf(final String code) {
        for (final NoOfferReason reason : values()) {
            if (reason.name().equals(code)) {
                return reason.title;
            }
        }
        return code;
    }
}
