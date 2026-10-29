package ru.smart.factoring.backend.factoring.ml;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Сервис моделей: PD покупателя и задержка по УПД (блок 4). Контракт - docs/claude/module-0/ml-service-openapi.yaml.
 * Реализации: {@link HttpModelClient} - сервис smart-factoring-ml, {@link StubModelClient} - встроенная заглушка (Р-35).
 */
public interface ModelClient {

    Versions versions();

    PdResponse scorePd(PdRequest request);

    DelayResponse scoreDelay(DelayRequest request);

    record Versions(String pd, String delay, boolean stub) {
    }

    record Financials(int year, Boolean simplified, BigDecimal assetsTotal, BigDecimal currentAssets, BigDecimal receivables,
                      BigDecimal cash, BigDecimal equity, BigDecimal longTermBorrowings, BigDecimal shortTermLiabilities,
                      BigDecimal shortTermBorrowings, BigDecimal payables, BigDecimal revenue, BigDecimal costOfSales,
                      BigDecimal salesProfit, BigDecimal interestPayable, BigDecimal netProfit) {
    }

    /**
     * @param lowDataReason причина нехватки данных, если её знает источник (партии ML-команды: low_data_reason в buyers.csv):
     *                      NO_RECENT_REPORT, LOW_REVENUE, YOUNG_COMPANY; вместе с разделом ОКВЭД задаёт базовый PD (П3-5)
     */
    record Buyer(String inn, String okved, String okvedSection, String region, LocalDate registrationDate,
                 List<Financials> financials, String lowDataReason) {
    }

    record PdRequest(LocalDate calcDate, List<Buyer> buyers) {
    }

    record Reason(String feature, String title, BigDecimal value, String direction, BigDecimal contribution) {
    }

    record PdResult(String inn, String status, BigDecimal pd, String pdSource, Boolean lowData, String lowDataReason,
                    List<Reason> reasons, String error) {
    }

    record PdResponse(String modelVersion, List<PdResult> results) {
    }

    record DelayUpd(String updId, String number, String clientInn, String clientIndustry, String buyerInn, BigDecimal buyerPd,
                    BigDecimal amount, BigDecimal outstanding, LocalDate updDate, LocalDate plannedDueDate, int deferralDays) {
    }

    /**
     * @param updId  id УПД - тот же, что у DelayUpd: история содержит и оцениваемые УПД, модель задержки их сопоставляет
     * @param number номер УПД - порядок УПД пары, подписанных в один день (модель задержки)
     */
    record HistoryUpd(String updId, String number, String clientInn, String buyerInn, BigDecimal amount, LocalDate updDate,
                      LocalDate plannedDueDate, String status, LocalDate paidDate, BigDecimal outstanding) {
    }

    record DelayRequest(LocalDate calcDate, int delayThresholdDays, List<Buyer> buyers, List<DelayUpd> upds,
                        List<HistoryUpd> history) {
    }

    record DelayResult(String updId, String status, BigDecimal pDelay, @JsonProperty("p_overdue_90") BigDecimal pOverdue90,
                       BigDecimal expectedDelayDays, Boolean noPairHistory, String error) {
    }

    record DelayResponse(String modelVersion, List<DelayResult> results) {
    }
}
