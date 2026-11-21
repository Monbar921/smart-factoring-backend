package ru.smart.factoring.backend.factoring.monitoring;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ru.smart.factoring.backend.api.monitoring.MonitoringDto;
import ru.smart.factoring.backend.factoring.error.ApiException;
import ru.smart.factoring.backend.factoring.json.Json;
import ru.smart.factoring.backend.factoring.run.RunRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Мониторинг моделей и LLM по запуску (Р-87, дорожная карта п. 1.3-12): всё считается из результатов запуска в БД,
 * отдельного хранилища метрик нет.
 * <ul>
 *     <li>версии моделей и LLM - run.model_versions;</li>
 *     <li>PD: распределение уровней A-D, доля базового PD, стоп-факторы, кэш; PSI PD модели к выборке разработки
 *     (эталон сервис моделей отдаёт в /v1/models, запуск хранит его в summary.pdReference) и PSI уровней к прошлому
 *     успешному запуску на той же версии модели;</li>
 *     <li>задержка: статусы, распределение вероятности задержки, PSI к прошлому запуску;</li>
 *     <li>LLM: тексты карточек по источникам и доля ответов, отклонённых проверкой фактов (метрики шага 5).</li>
 * </ul>
 * PSI = Σ (доля сейчас − доля в базе) · ln(доля сейчас / доля в базе); пустая корзина - 0,0001. Пороги - принятые в
 * банковской валидации моделей: меньше 0,1 - стабильно, 0,1–0,25 - наблюдать, от 0,25 - сдвиг, нужна перепроверка.
 */
@Service
@RequiredArgsConstructor
public class MonitoringService {
    static final BigDecimal WATCH = new BigDecimal("0.1");
    static final BigDecimal SHIFT = new BigDecimal("0.25");
    static final int MIN_RELIABLE = 100;
    private static final double EMPTY_SHARE = 0.0001;
    private static final int SCALE = 4;
    private static final int SHARE_SCALE = 6;
    private static final int HISTORY = 10;
    private static final int DECISION_STEP = 5;
    private static final List<String> LEVELS = List.of("A", "B", "C", "D");
    /** корзины вероятности задержки: верхние границы, последняя - до 1 включительно */
    private static final double[] DELAY_BOUNDS = {0.1, 0.2, 0.3, 0.5};
    private static final List<String> DELAY_LABELS = List.of("< 10 %", "10–20 %", "20–30 %", "30–50 %", "≥ 50 %");
    private static final String SUCCESS = "('DONE', 'DONE_WITH_WARNINGS')";

    private final NamedParameterJdbcTemplate jdbc;
    private final RunRepository runRepository;
    private final Json json;

    public MonitoringDto monitoring(final long runId) {
        final RunRepository.RunRow run = runRepository.findById(runId).orElseThrow(() -> ApiException.notFound("Запуск", runId));
        final Map<String, String> models = models(run);
        final PdStats pd = pdStats(run);
        final MonitoringDto.Delay delay = delay(run, models.get("delay"));
        final MonitoringDto.Llm llm = llm(runId, models.get("llm"));
        final List<MonitoringDto.HistoryPoint> history = new ArrayList<>();
        jdbc.query("select id from run where id <= :runId and status in " + SUCCESS + " order by id desc limit " + HISTORY,
                        new MapSqlParameterSource("runId", runId), (rs, rowNum) -> rs.getLong(1))
                .forEach(id -> runRepository.findById(id).ifPresent(row -> {
                    final PdStats stats = id == runId ? pd : pdStats(row);
                    final MonitoringDto.Llm texts = id == runId ? llm : llm(id, models(row).get("llm"));
                    history.add(new MonitoringDto.HistoryPoint(id, row.calcDate(), row.startedAt(), models(row).get("pd"),
                            stats.dto().buyers(), value(stats.dto().psiReference()), value(stats.dto().psiPrevious()),
                            texts.rejectedShare()));
                }));
        return new MonitoringDto(runId, run.status(), run.calcDate(), run.startedAt(), models, pd.dto(), delay, llm, history);
    }

    private Map<String, String> models(final RunRepository.RunRow run) {
        final Map<String, String> models = new TreeMap<>();
        final Map<String, Object> raw = json.readMap(run.modelVersions());
        if (raw != null) {
            raw.forEach((key, value) -> models.put(key, value == null ? null : value.toString()));
        }
        return models;
    }

    // ------------------------------------------------------------------ PD

    private PdStats pdStats(final RunRepository.RunRow run) {
        final List<BuyerScore> scores = jdbc.query("""
                        select risk_level, pd, pd_source, stop_factor, from_cache from buyer_score where run_id = :runId""",
                new MapSqlParameterSource("runId", run.id()),
                (rs, rowNum) -> new BuyerScore(rs.getString("risk_level"), rs.getBigDecimal("pd"), rs.getString("pd_source"),
                        rs.getString("stop_factor"), rs.getBoolean("from_cache")));
        final Map<String, Integer> levelCounts = counts(scores.stream().map(BuyerScore::riskLevel)
                .filter(level -> level != null && LEVELS.contains(level)).toList());
        final Map<String, Integer> bySource = new TreeMap<>();
        scores.stream().filter(score -> score.pdSource() != null)
                .forEach(score -> bySource.merge(score.pdSource(), 1, Integer::sum));
        final List<BigDecimal> pds = scores.stream().map(BuyerScore::pd).filter(Objects::nonNull).toList();
        final BigDecimal meanPd = pds.isEmpty() ? null
                : pds.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(pds.size()), SHARE_SCALE, RoundingMode.HALF_UP);

        final MonitoringDto.Reference reference = reference(run);
        final List<BigDecimal> cuts = reference != null ? reference.cuts() : null;
        final Map<String, Integer> modelCounts = counts(scores.stream()
                .filter(score -> "MODEL".equals(score.pdSource()) && score.pd() != null)
                .map(score -> cuts != null ? level(score.pd(), cuts) : score.riskLevel())
                .filter(level -> level != null && LEVELS.contains(level))
                .toList());
        final MonitoringDto.Psi psiReference = reference == null ? null
                : psi(shares(modelCounts), reference.shares(), "выборка разработки модели", null, total(modelCounts));

        MonitoringDto.Psi psiPrevious = null;
        final Long previous = previousRun(run.id(), "pd", models(run).get("pd"));
        if (previous != null) {
            final Map<String, Integer> before = counts(jdbc.query(
                    "select risk_level from buyer_score where run_id = :runId and risk_level is not null",
                    new MapSqlParameterSource("runId", previous), (rs, rowNum) -> rs.getString(1)));
            psiPrevious = psi(shares(levelCounts), shares(before), "запуск № " + previous, previous, total(levelCounts));
        }
        return new PdStats(new MonitoringDto.Pd(scores.size(), bySource, (int) scores.stream().filter(score -> score.stopFactor() != null).count(),
                (int) scores.stream().filter(BuyerScore::fromCache).count(), meanPd, buckets(levelCounts, LEVELS),
                buckets(modelCounts, LEVELS), psiReference, psiPrevious, reference));
    }

    private MonitoringDto.Reference reference(final RunRepository.RunRow run) {
        final Map<String, Object> summary = json.readMap(run.summary());
        final Object raw = summary != null ? summary.get("pdReference") : null;
        return raw == null ? null : json.read(json.write(raw), MonitoringDto.Reference.class);
    }

    static String level(final BigDecimal pd, final List<BigDecimal> cuts) {
        for (int i = 0; i < cuts.size() && i < LEVELS.size() - 1; i++) {
            if (pd.compareTo(cuts.get(i)) < 0) {
                return LEVELS.get(i);
            }
        }
        return LEVELS.getLast();
    }

    // ------------------------------------------------------------------ задержка

    private MonitoringDto.Delay delay(final RunRepository.RunRow run, final String version) {
        final List<UpdScore> scores = jdbc.query("select status, p_delay, delay_threshold_days from upd_score where run_id = :runId",
                new MapSqlParameterSource("runId", run.id()),
                (rs, rowNum) -> new UpdScore(rs.getString("status"), rs.getBigDecimal("p_delay"),
                        rs.getObject("delay_threshold_days", Integer.class)));
        final Map<String, Integer> byStatus = counts(scores.stream().map(UpdScore::status).toList());
        final Map<String, Integer> buckets = delayBuckets(scores.stream().map(UpdScore::pDelay).toList());
        final List<BigDecimal> values = scores.stream().map(UpdScore::pDelay).filter(Objects::nonNull).toList();
        final BigDecimal mean = values.isEmpty() ? null
                : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(values.size()), SHARE_SCALE, RoundingMode.HALF_UP);
        MonitoringDto.Psi psiPrevious = null;
        final Long previous = previousRun(run.id(), "delay", version);
        if (previous != null) {
            final Map<String, Integer> before = delayBuckets(jdbc.query("select p_delay from upd_score where run_id = :runId",
                    new MapSqlParameterSource("runId", previous), (rs, rowNum) -> rs.getBigDecimal(1)));
            psiPrevious = psi(shares(buckets), shares(before), "запуск № " + previous, previous, values.size());
        }
        final Integer threshold = scores.stream().map(UpdScore::thresholdDays).filter(Objects::nonNull).findFirst().orElse(null);
        return new MonitoringDto.Delay(scores.size(), byStatus, mean, threshold, buckets(buckets, DELAY_LABELS), psiPrevious);
    }

    private static Map<String, Integer> delayBuckets(final List<BigDecimal> values) {
        final Map<String, Integer> buckets = new LinkedHashMap<>();
        DELAY_LABELS.forEach(label -> buckets.put(label, 0));
        values.stream().filter(Objects::nonNull).forEach(value -> {
            int index = DELAY_BOUNDS.length;
            for (int i = 0; i < DELAY_BOUNDS.length; i++) {
                if (value.doubleValue() < DELAY_BOUNDS[i]) {
                    index = i;
                    break;
                }
            }
            buckets.merge(DELAY_LABELS.get(index), 1, Integer::sum);
        });
        return buckets;
    }

    // ------------------------------------------------------------------ LLM

    private MonitoringDto.Llm llm(final long runId, final String provider) {
        final List<String> raw = jdbc.query("select metrics::text from run_step where run_id = :runId and step_no = :stepNo",
                new MapSqlParameterSource().addValue("runId", runId).addValue("stepNo", DECISION_STEP), (rs, rowNum) -> rs.getString(1));
        final Map<String, Object> metrics = raw.isEmpty() || raw.getFirst() == null ? Map.of() : json.readMap(raw.getFirst());
        final Map<String, Integer> bySource = new TreeMap<>();
        if (metrics.get("textsBySource") instanceof Map<?, ?> sources) {
            sources.forEach((key, value) -> bySource.put(String.valueOf(key), integer(value)));
        }
        final int answers = integer(metrics.get("llmAnswers"));
        final int rejected = integer(metrics.get("llmRejected"));
        final BigDecimal share = answers > 0
                ? BigDecimal.valueOf(rejected).divide(BigDecimal.valueOf(answers), SCALE, RoundingMode.HALF_UP) : null;
        return new MonitoringDto.Llm(provider, integer(metrics.get("offers")), bySource, answers, rejected, share,
                integer(metrics.get("llmTemplateAfterFactCheck")));
    }

    // ------------------------------------------------------------------ общее

    /**
     * Прошлый успешный запуск на той же версии модели: другая версия - другое распределение, сравнивать нечестно.
     */
    private Long previousRun(final long runId, final String model, final String version) {
        if (version == null) {
            return null;
        }
        final List<Long> ids = jdbc.query("""
                        select id from run where id < :runId and status in %s and model_versions ->> :model = :version
                        order by id desc limit 1""".formatted(SUCCESS),
                new MapSqlParameterSource().addValue("runId", runId).addValue("model", model).addValue("version", version),
                (rs, rowNum) -> rs.getLong(1));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /**
     * PSI по корзинам: current и baseline - доли по одним и тем же подписям. Нет наблюдений - null.
     */
    static MonitoringDto.Psi psi(final Map<String, BigDecimal> current, final Map<String, BigDecimal> baseline, final String baselineName,
                                 final Long baselineRunId, final int n) {
        if (n == 0 || baseline == null || baseline.isEmpty() || baseline.values().stream().allMatch(share -> share.signum() == 0)) {
            return null;
        }
        final Set<String> keys = new TreeSet<>(baseline.keySet());
        keys.addAll(current.keySet());
        double sum = 0;
        for (final String key : keys) {
            final double actual = Math.max(current.getOrDefault(key, BigDecimal.ZERO).doubleValue(), EMPTY_SHARE);
            final double expected = Math.max(baseline.getOrDefault(key, BigDecimal.ZERO).doubleValue(), EMPTY_SHARE);
            sum += (actual - expected) * Math.log(actual / expected);
        }
        final BigDecimal value = BigDecimal.valueOf(sum).setScale(SCALE, RoundingMode.HALF_UP);
        return new MonitoringDto.Psi(value, status(value), baselineName, baselineRunId, n);
    }

    static String status(final BigDecimal psi) {
        if (psi.compareTo(WATCH) < 0) {
            return "STABLE";
        }
        return psi.compareTo(SHIFT) < 0 ? "WATCH" : "SHIFT";
    }

    private static Map<String, Integer> counts(final List<String> values) {
        final Map<String, Integer> counts = new TreeMap<>();
        values.stream().filter(Objects::nonNull).forEach(value -> counts.merge(value, 1, Integer::sum));
        return counts;
    }

    private static Map<String, BigDecimal> shares(final Map<String, Integer> counts) {
        final int total = total(counts);
        final Map<String, BigDecimal> shares = new LinkedHashMap<>();
        counts.forEach((key, count) -> shares.put(key, total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(total), SHARE_SCALE, RoundingMode.HALF_UP)));
        return shares;
    }

    private static int total(final Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static List<MonitoringDto.Bucket> buckets(final Map<String, Integer> counts, final List<String> labels) {
        final int total = total(counts);
        return labels.stream().map(label -> {
            final int count = counts.getOrDefault(label, 0);
            return new MonitoringDto.Bucket(label, count, total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(total), SCALE, RoundingMode.HALF_UP));
        }).toList();
    }

    private static int integer(final Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static BigDecimal value(final MonitoringDto.Psi psi) {
        return psi != null ? psi.value() : null;
    }

    private record PdStats(MonitoringDto.Pd dto) {
    }

    private record BuyerScore(String riskLevel, BigDecimal pd, String pdSource, String stopFactor, boolean fromCache) {
    }

    private record UpdScore(String status, BigDecimal pDelay, Integer thresholdDays) {
    }
}
