package ru.smart.factoring.backend.api.risk;

import java.time.LocalDate;

/**
 * Новое событие ЕФРСБ по покупателю (Р-86).
 *
 * @param buyerInn   ИНН покупателя
 * @param kind       что произошло
 * @param eventDate  дата события; null - последняя дата данных
 * @param caseNumber номер дела о банкротстве; null - незакрытое дело покупателя, если есть, иначе новый номер
 */
public record EfrsbEventRequest(String buyerInn, EfrsbEventKind kind, LocalDate eventDate, String caseNumber) {
}
