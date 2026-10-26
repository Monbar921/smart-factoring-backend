package ru.smart.factoring.backend.api.llm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Запрос сервиса моделей к LLM через backend (Р-48): модель и ключ выбирает backend.
 *
 * @param messages    диалог, по порядку
 * @param temperature 0..2, null - по умолчанию модели
 * @param maxTokens   предел длины ответа, null - по умолчанию модели
 */
public record LlmChatRequest(@NotEmpty List<@Valid LlmMessageDto> messages,
                             @DecimalMin("0") @DecimalMax("2") Double temperature,
                             @Positive Integer maxTokens) {
}
