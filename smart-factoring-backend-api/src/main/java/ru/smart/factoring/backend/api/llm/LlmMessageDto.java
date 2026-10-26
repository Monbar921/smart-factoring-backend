package ru.smart.factoring.backend.api.llm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Сообщение диалога с LLM.
 *
 * @param role    system, user или assistant
 * @param content текст
 */
public record LlmMessageDto(@NotBlank @Pattern(regexp = "system|user|assistant") String role, @NotBlank String content) {
}
