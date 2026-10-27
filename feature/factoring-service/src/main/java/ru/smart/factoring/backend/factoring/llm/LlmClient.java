package ru.smart.factoring.backend.factoring.llm;

import java.util.List;

/**
 * Доступ к LLM для всего контура: тексты карточек в прогоне и запросы сервиса моделей через backend
 * (smart-factoring-ml сам к GigaChat не ходит, ключ и лимиты - только в backend).
 * <p>
 * Сбои - ApiException с кодом LLM_DISABLED / LLM_UNAVAILABLE (503) или LLM_ERROR (502).
 */
public interface LlmClient {

    /**
     * Модель по умолчанию (GIGACHAT_MODEL стенда) - для журнала и версий (НФР-6), например GigaChat.
     */
    String model();

    Completion complete(CompletionRequest request);

    /**
     * Модели, доступные ключу (GigaChat GET /models): значения для GIGACHAT_MODEL.
     */
    List<String> models();

    /**
     * @param role system, user или assistant
     */
    record Message(String role, String content) {
    }

    /**
     * @param temperature null - по умолчанию модели
     * @param maxTokens   null - по умолчанию модели
     * @param model       модель для этого запроса; null или пусто - модель по умолчанию ({@link #model()})
     */
    record CompletionRequest(List<Message> messages, Double temperature, Integer maxTokens, String model) {

        public CompletionRequest(final List<Message> messages, final Double temperature, final Integer maxTokens) {
            this(messages, temperature, maxTokens, null);
        }
    }

    /**
     * @param finishReason stop, length, blacklist (ответ заменён из-за цензуры GigaChat) и др.
     */
    record Completion(String content, String model, String finishReason, Usage usage) {
    }

    record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }
}
