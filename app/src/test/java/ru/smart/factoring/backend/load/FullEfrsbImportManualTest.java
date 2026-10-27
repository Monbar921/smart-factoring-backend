package ru.smart.factoring.backend.load;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.factoring.scoring.StopFactors;
import ru.smart.factoring.backend.factoring.source.ManualUploadService;
import ru.smart.factoring.backend.support.TestDatabase;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ручная проверка импорта полного events.csv: mvn test -Defrsb.file=путь -Dtest=FullEfrsbImportManualTest.
 */
@EnabledIfSystemProperty(named = "efrsb.file", matches = ".+")
class FullEfrsbImportManualTest extends AbstractSmartFactoringBackendTest {

    @Autowired
    private ManualUploadService importService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void importsFullFile() {
        TestDatabase.clean(jdbcTemplate);
        final long started = System.currentTimeMillis();
        System.out.println("IMPORT " + importService.importEfrsb(Path.of(System.getProperty("efrsb.file"))).tables()
                + " in " + (System.currentTimeMillis() - started) + " ms");
        // повторный импорт того же файла (дельта, Р-52): всё «без изменений»
        final long again = System.currentTimeMillis();
        System.out.println("REIMPORT " + importService.importEfrsb(Path.of(System.getProperty("efrsb.file"))).tables()
                + " in " + (System.currentTimeMillis() - again) + " ms");
        final Map<String, Integer> classes = new TreeMap<>();
        jdbcTemplate.query("""
                select s.inn, s.status, k.stage, k.end_date
                from manual_upload_egrul_status s
                left join lateral (select * from manual_upload_bankruptcy_case bc where bc.inn = s.inn
                    order by (bc.end_date is null) desc, bc.start_date desc nulls last limit 1) k on true""", rs -> {
            final StopFactors.StopFactor factor = StopFactors.classify(rs.getString("status"), rs.getString("stage"),
                    rs.getObject("end_date", java.time.LocalDate.class));
            classes.merge(String.valueOf(factor), 1, Integer::sum);
        });
        System.out.println("STOP FACTORS " + classes);
        TestDatabase.clean(jdbcTemplate);
    }
}
