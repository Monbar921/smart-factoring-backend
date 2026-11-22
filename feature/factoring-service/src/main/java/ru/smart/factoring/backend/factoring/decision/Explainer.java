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
     * Версия текстов для отпечатка прогона (П4-3): провайдер и промпты. Другие промпты - другой отпечаток.
     */
    default String textsVersion() {
        return provider();
    }

    /**
     * @param source откуда тексты
     * @param error      причина сбоя LLM для source = FAILED (в журнал прогона)
     * @param llmAnswers ответов LLM на эту карточку в этом прогоне (с повторами)
     * @param rejected   из них отклонено проверкой фактов или искажённой меткой (Р-59, Р-73) - для мониторинга (Р-87)
     */
    record Texts(String reasonsText, String messageDraft, Source source, String error, int llmAnswers, int rejected) {

        public Texts(final String reasonsText, final String messageDraft, final Source source, final String error) {
            this(reasonsText, messageDraft, source, error, 0, 0);
        }

        public static Texts of(final String reasonsText, final String messageDraft, final Source source) {
            return new Texts(reasonsText, messageDraft, source, null);
        }

        public Texts counted(final int answers, final int rejectedAnswers) {
            return new Texts(reasonsText, messageDraft, source, error, answers, rejectedAnswers);
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
