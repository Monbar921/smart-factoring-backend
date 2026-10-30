package ru.smart.factoring.backend.api.run;

import java.time.LocalDate;
import java.util.List;

/**
 * Запуск прогона (ПР-2).
 *
 * @param clientInns ИНН клиентов прогона; null или пусто - все клиенты. ИНН, а не id: до первой загрузки
 *                   клиенты есть только в источнике (Р-29)
 * @param calcDate   дата расчёта; null - последняя дата данных (ПР-9)
 * @param force      true - «Прогнать заново»: запустить, даже если прогон на тех же данных уже есть (П4-3)
 */
public record StartRunRequest(List<String> clientInns, LocalDate calcDate, Boolean force) {
}
