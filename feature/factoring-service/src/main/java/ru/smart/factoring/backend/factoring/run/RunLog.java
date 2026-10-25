package ru.smart.factoring.backend.factoring.run;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.factoring.json.Json;

import java.util.List;
import java.util.Map;

/**
 * Журнал прогона и решений менеджера (ЭП-6, НФР-7).
 */
@Component
@RequiredArgsConstructor
public class RunLog {
    private static final String INSERT = """
            insert into run_log (run_id, level, step_no, event_code, client_id, buyer_id, upd_id, offer_id, message, details)
            values (:runId, :level, :stepNo, :eventCode, :clientId, :buyerId, :updId, :offerId, :message, cast(:details as jsonb))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;

    public void info(final Long runId, final Integer stepNo, final String eventCode, final String message) {
        write(List.of(Entry.of(runId, Level.INFO, stepNo, eventCode, message)));
    }

    public void warn(final Long runId, final Integer stepNo, final String eventCode, final String message) {
        write(List.of(Entry.of(runId, Level.WARN, stepNo, eventCode, message)));
    }

    public void error(final Long runId, final Integer stepNo, final String eventCode, final String message) {
        write(List.of(Entry.of(runId, Level.ERROR, stepNo, eventCode, message)));
    }

    public void write(final List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        final MapSqlParameterSource[] batch = entries.stream()
                .map(entry -> new MapSqlParameterSource()
                        .addValue("runId", entry.runId())
                        .addValue("level", entry.level().name())
                        .addValue("stepNo", entry.stepNo())
                        .addValue("eventCode", entry.eventCode())
                        .addValue("clientId", entry.clientId())
                        .addValue("buyerId", entry.buyerId())
                        .addValue("updId", entry.updId())
                        .addValue("offerId", entry.offerId())
                        .addValue("message", entry.message())
                        .addValue("details", json.write(entry.details())))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT, batch);
    }

    public enum Level {
        INFO,
        WARN,
        ERROR
    }

    /**
     * Запись журнала. Ссылки на клиента, покупателя, УПД и предложение необязательны.
     */
    public record Entry(Long runId, Level level, Integer stepNo, String eventCode, String message,
                        Long clientId, Long buyerId, Long updId, Long offerId, Map<String, Object> details) {

        public static Entry of(final Long runId, final Level level, final Integer stepNo, final String eventCode, final String message) {
            return new Entry(runId, level, stepNo, eventCode, message, null, null, null, null, null);
        }

        public Entry client(final Long id) {
            return new Entry(runId, level, stepNo, eventCode, message, id, buyerId, updId, offerId, details);
        }

        public Entry buyer(final Long id) {
            return new Entry(runId, level, stepNo, eventCode, message, clientId, id, updId, offerId, details);
        }

        public Entry upd(final Long id) {
            return new Entry(runId, level, stepNo, eventCode, message, clientId, buyerId, id, offerId, details);
        }

        public Entry offer(final Long id) {
            return new Entry(runId, level, stepNo, eventCode, message, clientId, buyerId, updId, id, details);
        }

        public Entry details(final Map<String, Object> value) {
            return new Entry(runId, level, stepNo, eventCode, message, clientId, buyerId, updId, offerId, value);
        }
    }
}
