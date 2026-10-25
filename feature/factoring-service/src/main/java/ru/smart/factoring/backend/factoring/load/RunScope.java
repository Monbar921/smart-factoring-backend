package ru.smart.factoring.backend.factoring.load;

import java.time.LocalDate;
import java.util.List;

/**
 * Что обрабатывает прогон после блока 1: клиенты прогона и срез по дате расчёта (ПР-4, ПР-9).
 * Следующие блоки берут УПД и платежи этих клиентов с датой не позже даты расчёта.
 *
 * @param clientIds    id клиентов прогона в основной таблице client
 * @param snapshotDate дата снимка внешних данных (ДД-6)
 */
public record RunScope(List<Long> clientIds, LocalDate snapshotDate) {
}
