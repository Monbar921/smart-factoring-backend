package ru.smart.factoring.backend.api.run;

import java.time.LocalDate;
import java.util.List;

/**
 * Запуск прогона (ПР-2).
 *
 * @param clientInns ИНН клиентов прогона; null или пусто - все клиенты. ИНН, а не id: до первой загрузки
 *                   клиенты есть только в источнике (Р-29)
 * @param calcDate   дата расчёта; null - последняя дата данных (ПР-9)
 */
public record StartRunRequest(List<String> clientInns, LocalDate calcDate) {
}
