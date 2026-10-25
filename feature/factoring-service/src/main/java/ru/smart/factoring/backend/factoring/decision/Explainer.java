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

    record Texts(String reasonsText, String messageDraft) {
    }
}
