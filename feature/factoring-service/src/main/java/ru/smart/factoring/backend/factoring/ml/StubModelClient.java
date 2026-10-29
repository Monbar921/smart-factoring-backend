package ru.smart.factoring.backend.factoring.ml;

import ru.smart.factoring.backend.factoring.source.Csv;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Встроенная заглушка сервиса моделей (Р-35): детерминированные ответы без обученных моделей.
 * <ul>
 *     <li>PD: из stub_buyer_scores.csv, иначе базовый PD 3% с «мало данных» по правилам п. 4.2, иначе 2%;</li>
 *     <li>задержка: из stub_upd_scores.csv (номер УПД + ИНН клиента), иначе P(задержка) 0,10, P(90+) 0,01, 3 дня;</li>
 *     <li>«нет истории оплат» и «нет истории пары» - по истории из запроса, как требует контракт (п. 4.5);</li>
 *     <li>три причины PD - по отчётности последнего года.</li>
 * </ul>
 */
public class StubModelClient implements ModelClient {
    static final String PD_VERSION = "pd-stub-1";
    static final String DELAY_VERSION = "delay-stub-1";
    private static final BigDecimal REVENUE_THRESHOLD = new BigDecimal("120000000");
    private static final BigDecimal BASE_PD = new BigDecimal("0.030000");
    private static final BigDecimal DEFAULT_PD = new BigDecimal("0.020000");
    private static final BigDecimal DEFAULT_DELAY = new BigDecimal("0.100000");
    private static final BigDecimal DEFAULT_OVERDUE_90 = new BigDecimal("0.010000");
    private static final BigDecimal DEFAULT_DELAY_DAYS = new BigDecimal("3");
    private static final int SCALE = 4;

    private final Map<String, PdResult> buyerOverrides = new HashMap<>();
    private final Map<String, DelayResult> updOverrides = new HashMap<>();

    public StubModelClient(final Path scoresDir) {
        if (scoresDir == null) {
            return;
        }
        final Path buyers = scoresDir.resolve("stub_buyer_scores.csv");
        if (Files.exists(buyers)) {
            Csv.read(buyers).rows().forEach(row -> buyerOverrides.put(row.get("inn"), new PdResult(row.get("inn"), "SCORED",
                    new BigDecimal(row.get("pd")), row.get("pd_source"), Boolean.parseBoolean(row.get("low_data")),
                    Boolean.parseBoolean(row.get("low_data")) ? "LOW_REVENUE" : null, null, null)));
        }
        final Path upds = scoresDir.resolve("stub_upd_scores.csv");
        if (Files.exists(upds)) {
            Csv.read(upds).rows().forEach(row -> updOverrides.put(row.get("number") + "|" + row.get("client_inn"),
                    new DelayResult(null, "SCORED", new BigDecimal(row.get("p_delay")), new BigDecimal(row.get("p_overdue_90")),
                            new BigDecimal(row.get("expected_delay_days")), false, null)));
        }
    }

    @Override
    public Versions versions() {
        return new Versions(PD_VERSION, DELAY_VERSION, true);
    }

    @Override
    public PdResponse scorePd(final PdRequest request) {
        final List<PdResult> results = new ArrayList<>();
        for (final Buyer buyer : request.buyers()) {
            final List<Reason> reasons = reasons(buyer);
            final PdResult override = buyerOverrides.get(buyer.inn());
            if (override != null) {
                results.add(new PdResult(buyer.inn(), "SCORED", override.pd(), override.pdSource(), override.lowData(),
                        override.lowDataReason(), reasons, null));
                continue;
            }
            final String lowDataReason = lowDataReason(buyer, request.calcDate());
            results.add(lowDataReason != null
                    ? new PdResult(buyer.inn(), "SCORED", BASE_PD, "BASE", true, lowDataReason, reasons, null)
                    : new PdResult(buyer.inn(), "SCORED", DEFAULT_PD, "MODEL", false, null, reasons, null));
        }
        return new PdResponse(PD_VERSION, results);
    }

    @Override
    public DelayResponse scoreDelay(final DelayRequest request) {
        final Set<String> buyersWithHistory = new HashSet<>();
        final Set<String> pairsWithHistory = new HashSet<>();
        request.history().stream().filter(history -> "PAID".equals(history.status())).forEach(history -> {
            buyersWithHistory.add(history.buyerInn());
            pairsWithHistory.add(history.clientInn() + "|" + history.buyerInn());
        });
        final List<DelayResult> results = new ArrayList<>();
        for (final DelayUpd upd : request.upds()) {
            if (!buyersWithHistory.contains(upd.buyerInn())) {
                results.add(new DelayResult(upd.updId(), "NO_PAYMENT_HISTORY", null, null, null, true, null));
                continue;
            }
            final boolean noPairHistory = !pairsWithHistory.contains(upd.clientInn() + "|" + upd.buyerInn());
            final DelayResult override = updOverrides.get(upd.number() + "|" + upd.clientInn());
            results.add(override != null
                    ? new DelayResult(upd.updId(), "SCORED", override.pDelay(), override.pOverdue90(), override.expectedDelayDays(),
                    noPairHistory, null)
                    : new DelayResult(upd.updId(), "SCORED", DEFAULT_DELAY, DEFAULT_OVERDUE_90, DEFAULT_DELAY_DAYS, noPairHistory, null));
        }
        return new DelayResponse(DELAY_VERSION, results);
    }

    /**
     * Базовый PD (п. 4.2): нет отчётности за прошлый год, выручка ниже порога модели или компания моложе года.
     */
    private static String lowDataReason(final Buyer buyer, final LocalDate calcDate) {
        // причина из источника важнее вычисленной - как в сервисе моделей
        if (buyer.lowDataReason() != null) {
            return buyer.lowDataReason();
        }
        final Financials last = lastYear(buyer, calcDate);
        if (last == null) {
            return "NO_RECENT_REPORT";
        }
        if (last.revenue() == null || last.revenue().compareTo(REVENUE_THRESHOLD) < 0) {
            return "LOW_REVENUE";
        }
        if (buyer.registrationDate() != null && buyer.registrationDate().isAfter(calcDate.minusYears(1))) {
            return "YOUNG_COMPANY";
        }
        return null;
    }

    private static Financials lastYear(final Buyer buyer, final LocalDate calcDate) {
        return buyer.financials() == null ? null : buyer.financials().stream()
                .filter(financials -> financials.year() == calcDate.getYear() - 1)
                .findFirst()
                .orElse(null);
    }

    /**
     * Три причины по последней отчётности: текущая ликвидность, рентабельность, доля собственного капитала.
     */
    private static List<Reason> reasons(final Buyer buyer) {
        final Financials last = buyer.financials() == null ? null : buyer.financials().stream()
                .max(Comparator.comparingInt(Financials::year))
                .orElse(null);
        if (last == null) {
            return List.of(new Reason("no_report", "Нет отчётности за последний год", null, "INCREASES_RISK", BigDecimal.ONE));
        }
        final List<Reason> reasons = new ArrayList<>();
        final BigDecimal currentRatio = ratio(last.currentAssets(), last.shortTermLiabilities());
        if (currentRatio != null) {
            reasons.add(new Reason("current_ratio", "Текущая ликвидность", currentRatio,
                    currentRatio.compareTo(BigDecimal.ONE) < 0 ? "INCREASES_RISK" : "DECREASES_RISK", new BigDecimal("0.40")));
        }
        final BigDecimal margin = ratio(last.netProfit(), last.revenue());
        if (margin != null) {
            reasons.add(new Reason("net_margin", "Маржа чистой прибыли", margin,
                    margin.signum() < 0 ? "INCREASES_RISK" : "DECREASES_RISK", new BigDecimal("0.30")));
        }
        final BigDecimal equityRatio = ratio(last.equity(), last.assetsTotal());
        if (equityRatio != null) {
            reasons.add(new Reason("equity_ratio", "Доля собственного капитала", equityRatio,
                    equityRatio.compareTo(new BigDecimal("0.2")) < 0 ? "INCREASES_RISK" : "DECREASES_RISK", new BigDecimal("0.20")));
        }
        return reasons;
    }

    private static BigDecimal ratio(final BigDecimal numerator, final BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
    }
}
