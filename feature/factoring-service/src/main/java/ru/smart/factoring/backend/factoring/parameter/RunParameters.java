package ru.smart.factoring.backend.factoring.parameter;

import ru.smart.factoring.backend.api.common.RiskLevel;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Параметры Приложения А BRD, прочитанные в начале прогона (Р-9). Снимок {@link #raw()} сохраняется в run.parameters.
 */
public record RunParameters(
        Map<String, String> raw,
        int maxDurationMinutes,
        int updRetryCount,
        BigDecimal pdRevenueThreshold,
        int delayThresholdDays,
        Map<RiskLevel, BigDecimal> riskLevelBounds,
        Map<RiskLevel, BigDecimal> advanceRate,
        Map<RiskLevel, BigDecimal> lgd,
        BigDecimal fundingRate,
        BigDecimal opexRate,
        BigDecimal marginRate,
        BigDecimal marketRate,
        int dayCountBasis,
        BigDecimal delayProbabilityThreshold,
        BigDecimal overdue90ProbabilityThreshold,
        BigDecimal minIncome,
        BigDecimal minOutstanding,
        int minDaysToDue,
        Map<RiskLevel, BigDecimal> buyerLimit,
        BigDecimal trBase,
        BigDecimal trMultiplier,
        BigDecimal trLiquidityThreshold,
        int trDeferralDaysThreshold,
        int offersPerClient,
        int reminderDays,
        BigDecimal newOfferNotificationThreshold,
        BigDecimal cashGapLiquidityThreshold,
        int manualMinutesPerClient,
        int systemMinutesPerClient,
        int workdayHours) {

    private static final RiskLevel[] BOUNDED_LEVELS = {RiskLevel.A, RiskLevel.B, RiskLevel.C};

    /**
     * Уровень риска по PD (п. 4.4, Р-65): граница относится к следующему, более рискованному уровню - как в модели PD.
     * A - PD меньше границы A, B - от границы A включительно до границы B, C - от границы B до границы C, D - граница C и выше.
     * Пример BRD п. 5.1 («PD 3% - уровень B») ошибочный, его поправят в следующей версии BRD (замечания 29.10, П3-4).
     */
    public RiskLevel riskLevel(final BigDecimal pd) {
        for (final RiskLevel level : BOUNDED_LEVELS) {
            if (pd.compareTo(riskLevelBounds.get(level)) < 0) {
                return level;
            }
        }
        return RiskLevel.D;
    }
}
