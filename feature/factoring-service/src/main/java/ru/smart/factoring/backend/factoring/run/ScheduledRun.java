package ru.smart.factoring.backend.factoring.run;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.api.run.InitiatorType;
import ru.smart.factoring.backend.api.run.StartRunRequest;
import ru.smart.factoring.backend.factoring.error.ApiException;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Ночной прогон по расписанию (Р-85): тот же запуск, что с экрана, от имени «по расписанию», по всем клиентам.
 * <p>
 * Блокировка ShedLock в таблице shedlock: при нескольких экземплярах backend прогон стартует один раз. Отказы - как
 * у оператора и в тот же журнал попыток: данные не изменились с прошлого прогона (отпечаток, П4-3), идёт другой
 * прогон (ПР-3). «Прогнать заново» расписание не нажимает: ночью без новых данных пересчитывать нечего.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledRun {
    public static final String INITIATOR = "по расписанию";
    public static final String ROLE = "система";
    private static final RunRepository.Initiator SCHEDULER = new RunRepository.Initiator(INITIATOR, ROLE, false);

    private final RunService runService;
    private final RunScheduleProperties properties;

    @Scheduled(cron = "${smart-factoring.schedule.cron:" + RunScheduleProperties.DEFAULT_CRON + "}",
            zone = "${smart-factoring.schedule.zone:" + RunScheduleProperties.DEFAULT_ZONE + "}")
    @SchedulerLock(name = "nightlyRun", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void nightly() {
        if (!properties.on()) {
            return;
        }
        startNow();
    }

    /**
     * Запуск, как его делает расписание. Возвращает номер прогона или null, если запуск отклонён (причина - в журнале
     * попыток).
     */
    public Long startNow() {
        final LocalDate calcDate = properties.today() ? LocalDate.now(ZoneId.of(properties.zoneOrDefault())) : null;
        try {
            final long runId = runService.start(new StartRunRequest(null, calcDate, false), InitiatorType.SCHEDULE, SCHEDULER);
            log.info("Ночной прогон {} запущен по расписанию", runId);
            return runId;
        } catch (ApiException e) {
            log.info("Ночной прогон не запущен: {}", e.getMessage());
            return null;
        }
    }
}
