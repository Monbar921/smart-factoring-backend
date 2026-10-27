package ru.smart.factoring.backend.factoring.run;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.smart.factoring.backend.api.run.InitiatorType;
import ru.smart.factoring.backend.api.run.RunStatus;
import ru.smart.factoring.backend.api.run.StepStatus;
import ru.smart.factoring.backend.factoring.error.ApiException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Таблицы run и run_step.
 */
@Repository
@RequiredArgsConstructor
public class RunRepository {
    private final JdbcClient jdbc;

    /**
     * Новый прогон в статусе RUNNING. Второй одновременный прогон не пускает индекс ux_run_single_running (ПР-3).
     */
    public long insertRunning(final LocalDate calcDate, final String clientInnsJson, final InitiatorType initiatorType,
                              final String initiator, final LocalDate dataDate, final LocalDate snapshotDate,
                              final String parametersJson) {
        try {
            return jdbc.sql("""
                            insert into run (status, calc_date, client_inns, initiator_type, initiator, data_date, snapshot_date, parameters)
                            values ('RUNNING', :calcDate, cast(:clientInns as jsonb), :initiatorType, :initiator, :dataDate, :snapshotDate,
                                    cast(:parameters as jsonb))
                            returning id""")
                    .param("calcDate", calcDate)
                    .param("clientInns", clientInnsJson)
                    .param("initiatorType", initiatorType.name())
                    .param("initiator", initiator)
                    .param("dataDate", dataDate)
                    .param("snapshotDate", snapshotDate)
                    .param("parameters", parametersJson)
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw new ApiException("RUN_ALREADY_RUNNING", HttpStatus.CONFLICT, "Прогон уже выполняется",
                    findRunningId().<Map<String, Object>>map(id -> Map.of("runId", id)).orElse(Map.of()));
        }
    }

    public void insertSteps(final long runId, final List<Integer> stepNos) {
        stepNos.forEach(stepNo -> jdbc.sql("insert into run_step (run_id, step_no, status) values (:runId, :stepNo, 'PENDING')")
                .param("runId", runId)
                .param("stepNo", stepNo)
                .update());
    }

    public void stepStarted(final long runId, final int stepNo) {
        jdbc.sql("update run_step set status = 'RUNNING', started_at = now() where run_id = :runId and step_no = :stepNo")
                .param("runId", runId)
                .param("stepNo", stepNo)
                .update();
    }

    /**
     * Прогресс долгой части шага - отдельным коротким запросом, вне транзакций шага: экран видит его сразу.
     */
    public void stepProgress(final long runId, final int stepNo, final String progressJson) {
        jdbc.sql("update run_step set progress = cast(:progress as jsonb) where run_id = :runId and step_no = :stepNo")
                .param("runId", runId)
                .param("stepNo", stepNo)
                .param("progress", progressJson)
                .update();
    }

    public void stepFinished(final long runId, final int stepNo, final StepStatus status, final String metricsJson) {
        jdbc.sql("""
                        update run_step set status = :status, finished_at = now(), metrics = cast(:metrics as jsonb)
                        where run_id = :runId and step_no = :stepNo""")
                .param("runId", runId)
                .param("stepNo", stepNo)
                .param("status", status.name())
                .param("metrics", metricsJson)
                .update();
    }

    public void finish(final long runId, final RunStatus status, final String summaryJson, final String modelVersionsJson,
                       final String errorMessage) {
        jdbc.sql("""
                        update run set status = :status, finished_at = now(), summary = cast(:summary as jsonb),
                                       model_versions = cast(:modelVersions as jsonb), error_message = :errorMessage
                        where id = :runId""")
                .param("runId", runId)
                .param("status", status.name())
                .param("summary", summaryJson)
                .param("modelVersions", modelVersionsJson)
                .param("errorMessage", errorMessage)
                .update();
    }

    public Optional<RunRow> findById(final long runId) {
        return jdbc.sql("select * from run where id = :runId").param("runId", runId).query(RunRow.class).optional();
    }

    public List<StepRow> findSteps(final long runId) {
        return jdbc.sql("select * from run_step where run_id = :runId order by step_no")
                .param("runId", runId)
                .query(StepRow.class)
                .list();
    }

    public List<RunRow> findPage(final int page, final int size) {
        return jdbc.sql("select * from run order by id desc limit :size offset :offset")
                .param("size", size)
                .param("offset", (long) page * size)
                .query(RunRow.class)
                .list();
    }

    public long count() {
        return jdbc.sql("select count(*) from run").query(Long.class).single();
    }

    public Optional<Long> findRunningId() {
        return jdbc.sql("select id from run where status = 'RUNNING'").query(Long.class).optional();
    }

    /**
     * Последний успешный прогон: его результаты действуют, пока следующий не завершится успешно (ПР-6).
     */
    public Optional<Long> findLastSuccessfulId() {
        return jdbc.sql("select max(id) from run where status in ('DONE', 'DONE_WITH_WARNINGS')").query(Long.class).optional();
    }

    /**
     * Прогоны RUNNING, прерванные остановкой приложения, переводятся в STOPPED (Р-28).
     */
    public List<Long> stopInterrupted(final String reason) {
        final List<Long> ids = jdbc.sql("select id from run where status = 'RUNNING'").query(Long.class).list();
        ids.forEach(id -> {
            jdbc.sql("update run_step set status = 'STOPPED', finished_at = now() where run_id = :runId and status = 'RUNNING'")
                    .param("runId", id)
                    .update();
            finish(id, RunStatus.STOPPED, null, null, reason);
        });
        return ids;
    }

    public record RunRow(long id, String status, LocalDate calcDate, String clientInns, String initiatorType, String initiator,
                         OffsetDateTime startedAt, OffsetDateTime finishedAt, LocalDate dataDate, LocalDate snapshotDate,
                         String parameters, String modelVersions, String summary, String errorMessage) {
    }

    public record StepRow(long runId, int stepNo, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                          String metrics, String progress) {
    }
}
