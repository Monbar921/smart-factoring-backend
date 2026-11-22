package ru.smart.factoring.backend.factoring.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import ru.smart.factoring.backend.api.run.RunScheduleDto;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Ночной прогон по расписанию (Р-85).
 *
 * @param enabled  true - прогон стартует сам по cron
 * @param cron     выражение cron Spring (секунды минуты часы день месяц день недели), по умолчанию 03:00 каждый день
 * @param zone     часовой пояс cron, по умолчанию Europe/Moscow
 * @param calcDate дата расчёта: data - последняя дата данных в источниках (как форма запуска), today - сегодня
 *                 в часовом поясе расписания (на пилоте, когда источники обновляются каждый день)
 */
@ConfigurationProperties("smart-factoring.schedule")
public record RunScheduleProperties(Boolean enabled, String cron, String zone, String calcDate) {
    public static final String DEFAULT_CRON = "0 0 3 * * *";
    public static final String DEFAULT_ZONE = "Europe/Moscow";

    public boolean on() {
        return Boolean.TRUE.equals(enabled);
    }

    public String cronOrDefault() {
        return cron == null || cron.isBlank() ? DEFAULT_CRON : cron.trim();
    }

    public String zoneOrDefault() {
        return zone == null || zone.isBlank() ? DEFAULT_ZONE : zone.trim();
    }

    public boolean today() {
        return "today".equalsIgnoreCase(calcDate == null ? "" : calcDate.trim());
    }

    /**
     * Расписание для формы запуска: включено ли и когда следующий запуск.
     */
    public RunScheduleDto info() {
        final ZoneId zoneId = ZoneId.of(zoneOrDefault());
        final ZonedDateTime next = on() ? CronExpression.parse(cronOrDefault()).next(ZonedDateTime.now(zoneId)) : null;
        return new RunScheduleDto(on(), cronOrDefault(), zoneId.getId(), next != null ? next.toOffsetDateTime() : null);
    }
}
