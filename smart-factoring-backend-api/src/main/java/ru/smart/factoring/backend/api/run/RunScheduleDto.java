package ru.smart.factoring.backend.api.run;

import java.time.OffsetDateTime;

/**
 * Запуск по расписанию (Р-85) для формы запуска.
 *
 * @param enabled true - ночной прогон включён
 * @param cron    выражение cron Spring: секунды минуты часы день месяц день недели
 * @param zone    часовой пояс расписания
 * @param nextAt  следующий запуск по расписанию; null - выключено
 */
public record RunScheduleDto(boolean enabled, String cron, String zone, OffsetDateTime nextAt) {
}
