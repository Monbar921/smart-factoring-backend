package ru.smart.factoring.backend.api.llm;

/**
 * Ответ LLM.
 *
 * @param content          текст ответа
 * @param model            модель, которая ответила
 * @param finishReason     stop, length, blacklist (GigaChat заменил ответ из-за цензуры) и др.
 * @param promptTokens     токенов во входе
 * @param completionTokens токенов в ответе
 */
public record LlmChatResponse(String content, String model, String finishReason, int promptTokens, int completionTokens) {
}
