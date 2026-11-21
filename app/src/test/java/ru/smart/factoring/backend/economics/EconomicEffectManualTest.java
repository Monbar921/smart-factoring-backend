package ru.smart.factoring.backend.economics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.factoring.source.ManualUploadService;
import ru.smart.factoring.backend.support.Api;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Числа для финансовой модели (Р-76): данные демо стенда (ZIP партий ML-команды) → прогон → экономический эффект.
 * mvn test -pl app -Dtest=EconomicEffectManualTest -DargLine="-Deconomics.dataset=../manual-upload/delivery.zip"
 */
@EnabledIfSystemProperty(named = "economics.dataset", matches = ".+")
class EconomicEffectManualTest extends AbstractSmartFactoringBackendTest {

    @Autowired
    private ManualUploadService importService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void printsEffectOnDemoData() throws IOException, InterruptedException {
        TestDatabase.clean(jdbcTemplate);
        try (InputStream zip = Files.newInputStream(Path.of(System.getProperty("economics.dataset")))) {
            importService.importGeneratorZip(zip);
        }
        final JsonNode run = runAndWait();
        run.get("steps").forEach(step -> System.out.println("ECONOMICS   step " + step.get("stepNo") + " " + step.get("durationMs") + " ms"));
        System.out.println("ECONOMICS run " + run.get("id") + " " + run.get("status") + " clients " + run.get("comparison"));
        System.out.println("ECONOMICS step5 " + Fixture.metrics(run, 5));
        final long runId = run.get("id").asLong();
        for (final String query : new String[] {"", "?fundingRate=0.12", "?fundingRate=0.20", "?trBase=0.05", "?trBase=0.15"}) {
            System.out.println("ECONOMICS " + (query.isEmpty() ? "base" : query) + " " + Api.get("/runs/" + runId + "/economics" + query).body());
        }
        final String out = System.getProperty("economics.out");
        if (out != null && !out.isBlank()) {
            // предложения запуска для финансовой модели: та же построчная формула п. 5.1 в xlsx
            final StringBuilder csv = new StringBuilder("ead;el;term_days;pd_term;lgd;tr;risk_level;price;income\n");
            jdbcTemplate.query("""
                    select ead_final, el_final, term_days, pd_term, lgd, tr, risk_level, price_final, income_final
                    from upd_decision where run_id = ? and decision = 'OFFER' order by upd_id""", rs -> {
                csv.append(rs.getBigDecimal(1)).append(';').append(rs.getBigDecimal(2)).append(';').append(rs.getInt(3)).append(';')
                        .append(rs.getBigDecimal(4)).append(';').append(rs.getBigDecimal(5)).append(';').append(rs.getBigDecimal(6))
                        .append(';').append(rs.getString(7)).append(';').append(rs.getBigDecimal(8)).append(';')
                        .append(rs.getBigDecimal(9)).append('\n');
            }, runId);
            Files.writeString(Path.of(out, "offers.csv"), csv);
            Files.writeString(Path.of(out, "economics.json"), Api.get("/runs/" + runId + "/economics").body().toString());
            Files.writeString(Path.of(out, "run.json"), run.toString());
        }
        System.out.println("ECONOMICS terms " + jdbcTemplate.queryForList("""
                select risk_level, count(*) n, round(avg(term_days), 1) avg_term, round(avg(ead_final)) avg_ead,
                       round(avg(income_final)) avg_income, round(avg(tr), 4) avg_tr
                from upd_decision where run_id = ? and decision = 'OFFER' group by risk_level order by risk_level""", runId));
        TestDatabase.clean(jdbcTemplate);
    }

    /**
     * Полный набор демо считается дольше, чем ждёт {@link Fixture#run}: ждём до 15 минут, иначе очистка БД пошла бы
     * параллельно с прогоном.
     */
    private static JsonNode runAndWait() throws InterruptedException {
        final long runId = Api.post("/runs", Map.of("force", true)).body().get("runId").asLong();
        final long deadline = System.currentTimeMillis() + 15 * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            final JsonNode run = Api.get("/runs/" + runId).body();
            if (!"RUNNING".equals(run.get("status").asString())) {
                return run;
            }
            Thread.sleep(2_000);
        }
        throw new AssertionError("Прогон " + runId + " не завершился за 15 минут");
    }
}
