package ru.smart.factoring.backend.factoring.decision;

/**
 * Тексты карточки предложения от LLM (п. 5.3): три причины простым языком и черновик сообщения клиенту.
 * Без LLM карточка показывается с причинами, но без черновика; предложение не блокируется.
 */
public interface Explainer {

    /**
     * @return тексты; null в полях - LLM недоступна или выключена
     */
    Texts explain(DecisionEngine.Decision decision);

    /**
     * Провайдер для журнала и версий модели (НФР-6).
     */
    String provider();

    /**
     * @param source откуда тексты
     * @param error  причина сбоя LLM для source = FAILED (в журнал прогона)
     */
    record Texts(String reasonsText, String messageDraft, Source source, String error) {

        public static Texts of(final String reasonsText, final String messageDraft, final Source source) {
            return new Texts(reasonsText, messageDraft, source, null);
        }
    }

    enum Source {
        /** ответ LLM в этом прогоне */
        LLM,
        /** ответ LLM из кеша прошлых прогонов (Р-7) */
        CACHE,
        /** шаблон без LLM: LLM_MODE = stub или off */
        TEMPLATE,
        /** LLM не ответила: причины шаблоном, без черновика */
        FAILED
    }
}
