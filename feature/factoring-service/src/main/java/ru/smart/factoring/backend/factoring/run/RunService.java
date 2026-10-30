package ru.smart.factoring.backend.factoring.run;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import ru.smart.factoring.backend.api.common.ClientRefDto;
import ru.smart.factoring.backend.api.common.PageDto;
import ru.smart.factoring.backend.api.run.InitiatorType;
import ru.smart.factoring.backend.api.run.ManualComparisonDto;
import ru.smart.factoring.backend.api.run.RunDefaultsDto;
import ru.smart.factoring.backend.api.run.RunAttemptDto;
import ru.smart.factoring.backend.api.run.RunDto;
import ru.smart.factoring.backend.api.run.RunListItemDto;
import ru.smart.factoring.backend.api.run.RunLogEntryDto;
import ru.smart.factoring.backend.api.run.RunStatus;
import ru.smart.factoring.backend.api.run.RunStepDto;
import ru.smart.factoring.backend.api.run.RunStepProgressDto;
import ru.smart.factoring.backend.api.run.StartRunRequest;
import ru.smart.factoring.backend.api.run.StepStatus;
import ru.smart.factoring.backend.factoring.error.ApiException;
import ru.smart.factoring.backend.factoring.json.Json;
import ru.smart.factoring.backend.factoring.parameter.ParameterService;
import ru.smart.factoring.backend.factoring.parameter.RunParameters;
import ru.smart.factoring.backend.factoring.source.SourceCatalog;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Запуск прогона и его состояние для экрана процесса (ПР-1…ПР-7, ЭП-1…ЭП-9).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final int MAX_PAGE_SIZE = 500;
    private static final DateTimeFormatter STARTED_AT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final RunRepository runRepository;
    private final RunExecutor runExecutor;
    private final RunLog runLog;
    private final ParameterService parameterService;
    private final SourceCatalog sourceCatalog;
    private final ThreadPoolTaskExecutor runTaskExecutor;
    private final JdbcClient jdbc;
    private final Json json;
    private final RunFingerprint runFingerprint;

    public RunDefaultsDto defaults() {
        return new RunDefaultsDto(sourceCatalog.dataDate().orElse(null), sourceCatalog.snapshotDate().orElse(null),
                sourceCatalog.eligibleClients(), runRepository.findRunningId().orElse(null), sourceCatalog.batches());
    }

    /**
     * Запускает прогон асинхронно и сразу возвращает его номер (ПР-2). Каждая попытка, и отклонённая, - в журнал попыток
     * (П4-6). Отказы: гость (403, П4-1), идёт другой прогон (409, ПР-3, П4-5), на тех же данных уже есть успешный прогон
     * и не нажато «Прогнать заново» (409, П4-3).
     */
    public long start(final StartRunRequest request, final InitiatorType initiatorType, final RunRepository.Initiator initiator) {
        try {
            final long runId = launch(request, initiatorType, initiator);
            runRepository.insertAttempt(initiator, initiatorType, "STARTED", null, null, runId);
            log.info("Прогон {} запущен: {} [{}]", runId, initiator.username(), initiator.role());
            return runId;
        } catch (ApiException e) {
            final Object runId = e.getDetails().get("runId");
            runRepository.insertAttempt(initiator, initiatorType, "REJECTED", e.getCode(), e.getMessage(),
                    runId instanceof Number number ? number.longValue() : null);
            log.warn("Запуск прогона отклонён: {} [{}] - {}", initiator.username(), initiator.role(), e.getMessage());
            throw e;
        }
    }

    public PageDto<RunAttemptDto> attempts(final int page, final int size) {
        final int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        final List<RunAttemptDto> items = runRepository.findAttempts(Math.max(page, 0), pageSize).stream()
                .map(row -> new RunAttemptDto(row.id(), row.attemptedAt(), row.initiator(), row.initiatorRole(), row.outcome(),
                        row.reason(), row.message(), row.runId()))
                .toList();
        return new PageDto<>(items, Math.max(page, 0), pageSize, runRepository.countAttempts());
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private long launch(final StartRunRequest request, final InitiatorType initiatorType, final RunRepository.Initiator initiator) {
        if (initiator.guest()) {
            throw new ApiException("FORBIDDEN", HttpStatus.FORBIDDEN, "Запуск прогона доступен менеджеру", Map.of());
        }
        final Long running = runRepository.findRunningId().orElse(null);
        if (running != null) {
            throw RunRepository.alreadyRunning(running);
        }
        final LocalDate dataDate = sourceCatalog.dataDate()
                .orElseThrow(() -> ApiException.validation("Нет данных в источниках: сначала загрузите данные (ручная загрузка)"));
        final LocalDate calcDate = request.calcDate() != null ? request.calcDate() : dataDate;
        final List<String> clientInns = normalizeClients(request.clientInns());
        final RunParameters parameters = parameterService.load();
        final LocalDate snapshotDate = sourceCatalog.snapshotDate().orElse(null);
        final String fingerprint = runFingerprint.of(calcDate, clientInns, dataDate, snapshotDate, parameters);
        if (fingerprint != null && !Boolean.TRUE.equals(request.force())) {
            runRepository.findDoneByFingerprint(fingerprint).ifPresent(done -> {
                throw new ApiException("RUN_ALREADY_DONE", HttpStatus.CONFLICT, "Эти данные уже прогнаны. Результат — прогон № "
                        + done.id() + " от " + done.startedAt().atZoneSameInstant(ZoneId.systemDefault()).format(STARTED_AT),
                        Map.of("runId", done.id(), "startedAt", done.startedAt().toString()));
            });
        }

        final long runId = runRepository.insertRunning(calcDate, json.write(clientInns), initiatorType, initiator, dataDate,
                snapshotDate, json.write(parameters.raw()), fingerprint);
        runRepository.insertSteps(runId, runExecutor.steps().stream().map(RunStep::stepNo).toList());

        final RunContext context = new RunContext(runId, calcDate, clientInns, parameters);
        runTaskExecutor.execute(() -> {
            try {
                runExecutor.execute(context);
            } catch (RuntimeException e) {
                // сбой самого исполнителя (например, БД недоступна): прогон не должен остаться RUNNING
                log.error("Прогон {} прерван", runId, e);
                runRepository.stopInterrupted("Прогон прерван: " + e.getMessage());
            }
        });
        return runId;
    }

    public RunDto get(final long runId) {
        final RunRepository.RunRow run = runRepository.findById(runId).orElseThrow(() -> ApiException.notFound("Прогон", runId));
        final Map<Integer, String> names = runExecutor.steps().stream()
                .collect(Collectors.toMap(RunStep::stepNo, RunStep::name));
        final List<RunStepDto> steps = runRepository.findSteps(runId).stream()
                .map(step -> new RunStepDto(step.stepNo(), names.get(step.stepNo()), StepStatus.valueOf(step.status()),
                        step.startedAt(), step.finishedAt(), durationMs(step.startedAt(), step.finishedAt()),
                        json.readMap(step.metrics()), json.read(step.progress(), RunStepProgressDto.class)))
                .toList();
        final Long durationMs = durationMs(run.startedAt(), run.finishedAt() != null ? run.finishedAt() : OffsetDateTime.now());
        return new RunDto(run.id(), RunStatus.valueOf(run.status()), run.calcDate(), json.read(run.clientInns(), STRING_LIST),
                InitiatorType.valueOf(run.initiatorType()), run.initiator(), run.startedAt(), run.finishedAt(), durationMs,
                run.dataDate(), run.snapshotDate(), json.readMap(run.modelVersions()), run.errorMessage(), steps,
                comparison(run, durationMs));
    }

    public PageDto<RunListItemDto> list(final int page, final int size) {
        final int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        final List<RunListItemDto> items = runRepository.findPage(Math.max(page, 0), pageSize).stream()
                .map(run -> {
                    final Map<String, Object> summary = summary(run);
                    return new RunListItemDto(run.id(), RunStatus.valueOf(run.status()), run.calcDate(), run.startedAt(),
                            run.finishedAt(), durationMs(run.startedAt(), run.finishedAt()), run.initiator(), run.initiatorRole(),
                            integer(summary.get("clientCount")), integer(summary.get("openUpdCount")),
                            integer(summary.get("offerCount")), decimal(summary.get("eadTotal")));
                })
                .toList();
        return new PageDto<>(items, Math.max(page, 0), pageSize, runRepository.count());
    }

    public PageDto<RunLogEntryDto> log(final long runId, final String level, final Integer stepNo, final int page, final int size) {
        runRepository.findById(runId).orElseThrow(() -> ApiException.notFound("Прогон", runId));
        final int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        final String where = " where run_id = :runId and (cast(:level as varchar) is null or level = :level)"
                + " and (cast(:stepNo as smallint) is null or step_no = :stepNo)";
        final List<RunLogEntryDto> items = jdbc.sql("select * from run_log" + where + " order by id limit :size offset :offset")
                .param("runId", runId)
                .param("level", level)
                .param("stepNo", stepNo)
                .param("size", pageSize)
                .param("offset", (long) Math.max(page, 0) * pageSize)
                .query((rs, rowNum) -> new RunLogEntryDto(rs.getLong("id"), rs.getObject("logged_at", OffsetDateTime.class),
                        rs.getString("level"), rs.getObject("step_no", Integer.class), rs.getString("event_code"),
                        rs.getString("message"), rs.getObject("client_id", Long.class), rs.getObject("buyer_id", Long.class),
                        rs.getObject("upd_id", Long.class), rs.getObject("offer_id", Long.class),
                        json.readMap(rs.getString("details"))))
                .list();
        final long total = jdbc.sql("select count(*) from run_log" + where)
                .param("runId", runId)
                .param("level", level)
                .param("stepNo", stepNo)
                .query(Long.class)
                .single();
        return new PageDto<>(items, Math.max(page, 0), pageSize, total);
    }

    /**
     * Прогоны, прерванные остановкой приложения, не должны блокировать новый запуск (Р-28).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void stopInterruptedRuns() {
        runRepository.stopInterrupted("Прогон прерван перезапуском backend").forEach(runId -> {
            log.warn("Прогон {} прерван перезапуском и переведён в STOPPED", runId);
            runLog.error(runId, null, "RUN_INTERRUPTED", "Прогон прерван перезапуском backend");
        });
    }

    private List<String> normalizeClients(final List<String> clientInns) {
        if (clientInns == null || clientInns.isEmpty()) {
            return null;
        }
        final Set<String> known = sourceCatalog.eligibleClients().stream().map(ClientRefDto::inn).collect(Collectors.toSet());
        final Set<String> unknown = new HashSet<>(clientInns);
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw ApiException.validation("Нет клиентов с ИНН " + unknown);
        }
        return clientInns.stream().distinct().sorted().toList();
    }

    private ManualComparisonDto comparison(final RunRepository.RunRow run, final Long durationMs) {
        final Map<String, Object> summary = summary(run);
        final Integer clients = integer(summary.get("clientCount"));
        if (clients == null) {
            return null;
        }
        final RunParameters parameters = parameterService.fromRaw(json.read(run.parameters(), STRING_MAP));
        return new ManualComparisonDto(clients, (long) clients * parameters.manualMinutesPerClient(),
                (long) clients * parameters.systemMinutesPerClient(), durationMs);
    }

    private Map<String, Object> summary(final RunRepository.RunRow run) {
        final Map<String, Object> summary = json.readMap(run.summary());
        return summary != null ? summary : Map.of();
    }

    private static Long durationMs(final OffsetDateTime from, final OffsetDateTime to) {
        return from != null && to != null ? Duration.between(from, to).toMillis() : null;
    }

    private static Integer integer(final Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static BigDecimal decimal(final Object value) {
        return value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }
}
