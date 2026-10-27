package ru.smart.factoring.backend.api.llm;

import java.util.List;

/**
 * Модели LLM.
 *
 * @param configured модель из настроек backend (GIGACHAT_MODEL)
 * @param available  модели, доступные ключу GigaChat
 */
public record LlmModelsDto(String configured, List<String> available) {
}
