package ru.smart.factoring.backend.factoring.scoring;

import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.factoring.ml.ModelClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Результат блока 4 для блока 5 (п. 4.4).
 *
 * @param buyers по id покупателя (только покупатели из реестра)
 * @param upds   по id УПД (оценённые моделью задержки или с ошибкой оценки)
 */
public record ScoringResult(Map<Long, BuyerScore> buyers, Map<Long, UpdScore> upds) {

    /**
     * Оценка покупателя.
     *
     * @param pd    null - стоп-фактор или ошибка оценки PD
     * @param error ошибка оценки PD после повторов (НФР-4.1)
     */
    public record BuyerScore(long buyerId, String inn, StopFactors.StopFactor stopFactor, boolean hasPaymentHistory,
                             BigDecimal pd, String pdSource, boolean lowData, RiskLevel riskLevel,
                             List<ModelClient.Reason> reasons, String modelVersion, LocalDate dataDate, boolean fromCache,
                             String error) {
    }

    /**
     * Оценка УПД моделью задержки.
     *
     * @param status SCORED, NO_PAYMENT_HISTORY или ERROR
     */
    public record UpdScore(long updId, String status, BigDecimal pDelay, BigDecimal pOverdue90, BigDecimal expectedDelayDays,
                           boolean noPairHistory, String modelVersion, int attempts, String error) {

        public boolean scored() {
            return "SCORED".equals(status);
        }
    }
}
