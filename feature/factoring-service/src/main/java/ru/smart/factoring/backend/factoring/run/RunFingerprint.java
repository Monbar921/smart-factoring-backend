package ru.smart.factoring.backend.factoring.run;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.factoring.decision.Explainer;
import ru.smart.factoring.backend.factoring.json.Json;
import ru.smart.factoring.backend.factoring.ml.ModelClient;
import ru.smart.factoring.backend.factoring.parameter.RunParameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Отпечаток прогона (замечания 30.10, П4-3): на чём он сделан. Одинаковый отпечаток - тот же результат, повторный прогон
 * без «Прогнать заново» не запускается.
 * <p>
 * В отпечатке: содержимое таблиц ручной загрузки (партии и их контрольные суммы попадают в них построчно), дата данных
 * и среза, дата расчёта, набор клиентов, параметры, версии моделей сервиса моделей и версия текстов LLM (провайдер,
 * PROMPT_VERSION и тексты промптов). Сервис моделей не ответил - отпечатка нет: прогон запускается без проверки.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunFingerprint {
    /** таблицы источника; manual_upload_batch - журнал импортов (время импорта), в отпечаток не входит */
    private static final List<String> SOURCES = List.of(
            "manual_upload_manager", "manual_upload_client", "manual_upload_buyer", "manual_upload_buyer_financials",
            "manual_upload_contract", "manual_upload_upd", "manual_upload_payment", "manual_upload_egrul_status",
            "manual_upload_bankruptcy_case", "manual_upload_efrsb_event");

    private final JdbcClient jdbc;
    private final ModelClient modelClient;
    private final Explainer explainer;
    private final Json json;

    /**
     * @return SHA-256 отпечатка; null - версии моделей неизвестны (сервис моделей не ответил)
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    public String of(final LocalDate calcDate, final List<String> clientInns, final LocalDate dataDate,
                     final LocalDate snapshotDate, final RunParameters parameters) {
        final ModelClient.Versions versions;
        try {
            versions = modelClient.versions();
        } catch (RuntimeException e) {
            log.warn("Отпечаток прогона не посчитан: сервис моделей не ответил ({})", e.getMessage());
            return null;
        }
        final Map<String, Object> input = new LinkedHashMap<>();
        input.put("sources", sourcesHash());
        input.put("dataDate", String.valueOf(dataDate));
        input.put("snapshotDate", String.valueOf(snapshotDate));
        input.put("calcDate", String.valueOf(calcDate));
        input.put("clients", clientInns);
        input.put("parameters", new TreeMap<>(parameters.raw()));
        input.put("pdModel", versions.pd());
        input.put("delayModel", versions.delay());
        input.put("texts", explainer.textsVersion());
        return sha256(json.write(input));
    }

    /**
     * Хеш содержимого источника без суррогатных id: повторный импорт тех же данных отпечаток не меняет.
     */
    private String sourcesHash() {
        final String rows = SOURCES.stream()
                .map(table -> "select '" + table + "' || md5((to_jsonb(t) - 'id')::text) as h from " + table + " t")
                .collect(Collectors.joining(" union all "));
        return jdbc.sql("select coalesce(md5(string_agg(h, ',' order by h)), '') from (" + rows + ") s").query(String.class).single();
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
