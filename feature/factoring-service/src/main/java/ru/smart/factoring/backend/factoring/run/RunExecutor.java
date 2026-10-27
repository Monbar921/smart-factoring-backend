package ru.smart.factoring.backend.factoring.run;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.api.run.RunStatus;
import ru.smart.factoring.backend.api.run.StepStatus;
import ru.smart.factoring.backend.factoring.json.Json;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Выполняет шаги прогона по порядку (ПР-5). Сбой шага останавливает прогон, результаты прошлого успешного
 * прогона остаются в силе (ПР-6).
 */
@Slf4j
@Component
public class RunExecutor {
    private final List<RunStep> steps;
    private final RunRepository runRepository;
    private final RunLog runLog;
    private final Json json;

    public RunExecutor(final List<RunStep> steps, final RunRepository runRepository, final RunLog runLog, final Json json) {
        this.steps = steps.stream().sorted(Comparator.comparingInt(RunStep::stepNo)).toList();
        this.runRepository = runRepository;
        this.runLog = runLog;
        this.json = json;
    }

    public List<RunStep> steps() {
        return steps;
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    public void execute(final RunContext context) {
        final long runId = context.getRunId();
        final long started = System.nanoTime();
        runLog.info(runId, null, "RUN_STARTED", "Прогон запущен, дата расчёта " + context.getCalcDate());
        context.onProgress((stepNo, snapshot) -> saveProgress(runId, stepNo, snapshot));
        for (final RunStep step : steps) {
            runRepository.stepStarted(runId, step.stepNo());
            final RunStep.Result result;
            try {
                result = step.execute(context);
            } catch (RuntimeException e) {
                log.error("Прогон {}: сбой шага {}", runId, step.stepNo(), e);
                final String message = "Сбой шага " + step.stepNo() + " «" + step.name() + "»: " + e.getMessage();
                runRepository.stepFinished(runId, step.stepNo(), StepStatus.STOPPED, null);
                runLog.error(runId, step.stepNo(), "STEP_FAILED", message);
                runRepository.finish(runId, RunStatus.STOPPED, json.write(context.getSummary()),
                        json.write(context.getModelVersions()), message);
                return;
            }
            if (result.warning()) {
                context.markWarning();
            }
            runRepository.stepFinished(runId, step.stepNo(), result.warning() ? StepStatus.WARNING : StepStatus.DONE,
                    json.write(result.metrics()));
        }
        final RunStatus status = context.hasWarnings() ? RunStatus.DONE_WITH_WARNINGS : RunStatus.DONE;
        runRepository.finish(runId, status, json.write(context.getSummary()), json.write(context.getModelVersions()), null);
        final Duration duration = Duration.ofNanos(System.nanoTime() - started);
        runLog.info(runId, null, "RUN_FINISHED", "Прогон завершён: " + status + ", " + duration.toMillis() + " мс");
        if (duration.toMinutes() >= context.getParameters().maxDurationMinutes()) {
            runLog.warn(runId, null, "RUN_TOO_LONG", "Прогон дольше " + context.getParameters().maxDurationMinutes() + " минут (НФР-1.1)");
        }
    }

    /**
     * Прогресс - только для экрана: сбой его записи не должен ронять шаг (пишется и из потоков текстов LLM).
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private void saveProgress(final long runId, final int stepNo, final StepProgress.Snapshot snapshot) {
        try {
            runRepository.stepProgress(runId, stepNo, json.write(snapshot));
        } catch (RuntimeException e) {
            log.warn("Прогон {}: прогресс шага {} не записан: {}", runId, stepNo, e.getMessage());
        }
    }
}
