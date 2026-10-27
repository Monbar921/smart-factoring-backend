package ru.smart.factoring.backend.api.run;

import java.time.OffsetDateTime;

/**
 * Прогресс долгой части шага (тексты LLM, оценка моделей): экран считает по нему процент и оставшееся время.
 *
 * @param label что считается («Тексты карточек (LLM)»)
 * @param done  сделано
 * @param total всего
 * @param since начало этой части шага: скорость = done / (сейчас - since)
 */
public record RunStepProgressDto(String label, int done, int total, OffsetDateTime since) {
}
