package ru.smart.factoring.backend.factoring.scoring;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Стоп-факторы покупателя (п. 4.1) по данным ЕГРЮЛ и ЕФРСБ на дату снимка: банкротство, ликвидация, исключение из ЕГРЮЛ.
 * <p>
 * Статус ЕГРЮЛ проверяется по ключевым словам (значения - из events.csv), затем - незакрытое дело о банкротстве
 * в активной стадии. Прекращённое или завершённое дело и реорганизация стоп-фактором не являются (data-model.md).
 * Дополнительный источник «риск-мониторинг банка» в MVP не подключён.
 */
public final class StopFactors {
    private static final Set<String> ACTIVE_STAGES = Set.of(
            "наблюдение", "финансовое оздоровление", "внешнее управление", "конкурсное производство");

    private StopFactors() {
    }

    public static StopFactor classify(final String egrulStatus, final String caseStage, final LocalDate caseEndDate) {
        final String status = egrulStatus == null ? "" : egrulStatus.toLowerCase(Locale.ROOT);
        if (status.contains("исключ")) {
            return StopFactor.EXCLUSION;
        }
        if (status.contains("ликвид")) {
            return StopFactor.LIQUIDATION;
        }
        if (status.contains("банкрот") || status.contains("несостоятельн") || status.contains("наблюдение")
                || status.contains("конкурсное производство") || status.contains("внешнее управление")) {
            return StopFactor.BANKRUPTCY;
        }
        if (caseStage != null && caseEndDate == null && ACTIVE_STAGES.contains(caseStage.toLowerCase(Locale.ROOT).trim())) {
            return StopFactor.BANKRUPTCY;
        }
        return null;
    }

    public enum StopFactor {
        BANKRUPTCY,
        LIQUIDATION,
        EXCLUSION
    }
}
