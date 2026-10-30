package ru.smart.factoring.backend.support;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Фикстура manual-upload/fixture-v1 (CASES.md): импорт и запуск прогона через API.
 */
public final class Fixture {
    public static final String PATH = "fixture-v1";
    private static final long POLL_MS = 100;
    private static final int MAX_POLLS = 600;

    private Fixture() {
    }

    public static void importFixture(final JdbcTemplate jdbcTemplate) {
        TestDatabase.clean(jdbcTemplate);
        TestDatabase.useFixtureRiskBounds(jdbcTemplate);
        final Api.Response response = Api.post("/manual-upload", Map.of("path", PATH));
        if (response.status() != 200) {
            throw new AssertionError("Импорт фикстуры: " + response.status() + " " + response.body());
        }
    }

    /**
     * Запускает прогон и ждёт его завершения; возвращает прогон из GET /runs/{id}. Тесты прогоняют те же данные по
     * нескольку раз, поэтому - с «Прогнать заново» (force), если тест не задал его сам (П4-3).
     */
    public static JsonNode run(final Map<String, Object> request) throws InterruptedException {
        final Map<String, Object> body = new HashMap<>(request);
        body.putIfAbsent("force", true);
        final Api.Response started = Api.post("/runs", body);
        if (started.status() != 202) {
            throw new AssertionError("Запуск прогона: " + started.status() + " " + started.body());
        }
        final long runId = started.body().get("runId").asLong();
        for (int i = 0; i < MAX_POLLS; i++) {
            final JsonNode run = Api.get("/runs/" + runId).body();
            if (!"RUNNING".equals(run.get("status").asString())) {
                return run;
            }
            Thread.sleep(POLL_MS);
        }
        throw new AssertionError("Прогон " + runId + " не завершился");
    }

    public static JsonNode metrics(final JsonNode run, final int stepNo) {
        return run.get("steps").get(stepNo - 1).get("metrics");
    }
}
