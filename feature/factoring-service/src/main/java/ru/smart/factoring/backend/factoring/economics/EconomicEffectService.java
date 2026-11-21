package ru.smart.factoring.backend.factoring.economics;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ru.smart.factoring.backend.api.economics.EconomicEffectDto;
import ru.smart.factoring.backend.factoring.decision.Economics;
import ru.smart.factoring.backend.factoring.error.ApiException;
import ru.smart.factoring.backend.factoring.json.Json;
import ru.smart.factoring.backend.factoring.parameter.ParameterService;
import ru.smart.factoring.backend.factoring.parameter.RunParameters;
import ru.smart.factoring.backend.factoring.run.RunRepository;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Экономический эффект по прогону (дорожная карта, п. 1.2; Р-76). Цепочка: предложения прогона → TR (вероятность, что
 * клиент согласится) → процентный доход − фондирование − ожидаемые потери − опер. расходы = доход банка → за год по
 * оборачиваемости дебиторки → три масштаба → минус сопровождение → окупаемость внедрения.
 * <p>
 * Предложения пересчитываются тем же {@link Economics}, что в прогоне, с EL из прогона: при ставках прогона суммы
 * совпадают с итогами шага 5 до копейки. Набор предложений тот же: при другой ставке фондирования предложение, доход
 * по которому стал ниже минимального, выпадает (как выпало бы в прогоне); остальные правила п. 5.2 от ставок не зависят.
 * Изменение базового TR меняет TR каждого предложения в том же отношении (повышающие признаки п. 5.3 сохраняются).
 */
@Service
@RequiredArgsConstructor
public class EconomicEffectService {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);
    private static final BigDecimal MINUTES_IN_HOUR = BigDecimal.valueOf(60);
    private static final BigDecimal MONTHS_IN_YEAR = BigDecimal.valueOf(12);
    private static final int MONEY = 2;
    private static final int RATIO = 4;
    /** без настройки - те же значения, что в application.yml и docs/economics.md */
    private static final BigDecimal IMPLEMENTATION_COST = new BigDecimal("18600000");
    private static final BigDecimal FIXED_ANNUAL_COST = new BigDecimal("10800000");
    private static final BigDecimal COST_PER_CLIENT = new BigDecimal("600");
    private static final BigDecimal MANAGER_HOUR_COST = new BigDecimal("1500");
    private static final int CORE_CLIENTS = 5_759;
    private static final int SEGMENT_CLIENTS = 22_641;
    private static final int REVIEWS_PER_YEAR = 4;
    private static final int FTE_HOURS_PER_YEAR = 1_970;

    private final NamedParameterJdbcTemplate jdbc;
    private final RunRepository runRepository;
    private final ParameterService parameterService;
    private final EconomicsProperties properties;
    private final Json json;

    /**
     * @param fundingRate ставка фондирования; null - ставка прогона
     * @param trBase      базовый TR; null - TR прогона
     */
    public EconomicEffectDto effect(final long runId, final BigDecimal fundingRate, final BigDecimal trBase) {
        final RunRepository.RunRow run = runRepository.findById(runId).orElseThrow(() -> ApiException.notFound("Прогон", runId));
        if ("RUNNING".equals(run.status())) {
            throw new ApiException("RUN_NOT_FINISHED", HttpStatus.CONFLICT,
                    "Анализ дебиторки ещё идёт: экономический эффект будет после завершения", Map.of("runId", runId));
        }
        final RunParameters parameters = parameterService.fromRaw(json.read(run.parameters(), STRING_MAP));
        final BigDecimal funding = rate(fundingRate, parameters.fundingRate(), "fundingRate");
        final BigDecimal tr = rate(trBase, parameters.trBase(), "trBase");
        final List<OfferRow> offers = offers(runId);
        final Economics.Rates runRates = Economics.Rates.of(parameters);
        final EconomicEffectDto.Offers base = offers(offers, runRates, runRates, BigDecimal.ONE, parameters.minIncome());
        final EconomicEffectDto.Offers result = offers(offers, runRates.withFunding(funding), runRates,
                tr.divide(parameters.trBase(), PRECISION), parameters.minIncome());

        final int clients = clients(run);
        final BigDecimal turnover = result.avgTermDays().signum() == 0 ? BigDecimal.ZERO
                : DAYS_IN_YEAR.divide(result.avgTermDays(), PRECISION);
        final EconomicEffectDto.Year year = new EconomicEffectDto.Year(turnover.setScale(RATIO, RoundingMode.HALF_UP),
                money(result.expectedEad().multiply(turnover)), money(result.income().multiply(turnover)));
        final EconomicEffectDto.Assumptions assumptions = assumptions(parameters);
        final EconomicEffectDto.Time time = time(clients, assumptions);

        final List<EconomicEffectDto.Scale> scales = new ArrayList<>();
        scales.add(scale("DEMO", "Демо: клиенты прогона", clients, clients, year, time, assumptions));
        scales.add(scale("CORE", "«Ядро»: деньги застряли у покупателей", assumptions.coreClients(), clients, year, time, assumptions));
        scales.add(scale("SEGMENT", "Весь сегмент: выручка 0,8–2 млрд ₽", assumptions.segmentClients(), clients, year, time, assumptions));
        return new EconomicEffectDto(runId, run.calcDate(), clients,
                new EconomicEffectDto.Rates(parameters.fundingRate(), parameters.trBase()), new EconomicEffectDto.Rates(funding, tr),
                base, result, year, time, scales, assumptions);
    }

    private List<OfferRow> offers(final long runId) {
        return jdbc.query("""
                        select ead_final, el_final, term_days, pd_term, lgd, tr from upd_decision
                        where run_id = :runId and decision = 'OFFER'""", new MapSqlParameterSource("runId", runId),
                (rs, rowNum) -> new OfferRow(rs.getBigDecimal("ead_final"), rs.getBigDecimal("el_final"), rs.getInt("term_days"),
                        rs.getBigDecimal("pd_term"), rs.getBigDecimal("lgd"), rs.getBigDecimal("tr")));
    }

    /**
     * @param runRates ставки прогона: предложение выпадает, только если доход стал ниже минимального из-за новых ставок
     *                 (после деления лимита доход бывает ниже минимального и в самом прогоне - такое предложение остаётся)
     * @param trFactor во сколько раз меняется TR каждого предложения
     */
    private static EconomicEffectDto.Offers offers(final List<OfferRow> offers, final Economics.Rates rates,
                                                   final Economics.Rates runRates, final BigDecimal trFactor,
                                                   final BigDecimal minIncome) {
        int count = 0;
        int belowMin = 0;
        BigDecimal portfolioEad = BigDecimal.ZERO;
        BigDecimal portfolioIncome = BigDecimal.ZERO;
        BigDecimal deals = BigDecimal.ZERO;
        BigDecimal ead = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal funding = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;
        BigDecimal opex = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal termWeighted = BigDecimal.ZERO;
        for (final OfferRow offer : offers) {
            final Economics.Result economics = Economics.price(offer.ead(), offer.termDays(), offer.pdTerm(), offer.lgd(), offer.el(), rates);
            if (economics.income().compareTo(minIncome) < 0 && Economics.price(offer.ead(), offer.termDays(), offer.pdTerm(),
                    offer.lgd(), offer.el(), runRates).income().compareTo(minIncome) >= 0) {
                belowMin++;
                continue;
            }
            final BigDecimal tr = offer.tr().multiply(trFactor, PRECISION).min(BigDecimal.ONE);
            count++;
            portfolioEad = portfolioEad.add(offer.ead());
            portfolioIncome = portfolioIncome.add(economics.income());
            deals = deals.add(tr);
            ead = ead.add(tr.multiply(offer.ead(), PRECISION));
            revenue = revenue.add(tr.multiply(economics.price(), PRECISION));
            funding = funding.add(tr.multiply(economics.funding(), PRECISION));
            loss = loss.add(tr.multiply(economics.el(), PRECISION));
            opex = opex.add(tr.multiply(economics.opex(), PRECISION));
            income = income.add(tr.multiply(economics.income(), PRECISION));
            termWeighted = termWeighted.add(offer.ead().multiply(BigDecimal.valueOf(offer.termDays()), PRECISION));
        }
        final BigDecimal avgTerm = portfolioEad.signum() == 0 ? BigDecimal.ZERO : termWeighted.divide(portfolioEad, PRECISION);
        return new EconomicEffectDto.Offers(count, belowMin, money(portfolioEad), money(portfolioIncome),
                deals.setScale(RATIO, RoundingMode.HALF_UP), money(ead), money(revenue), money(funding), money(loss), money(opex),
                money(income), avgTerm.setScale(1, RoundingMode.HALF_UP));
    }

    private EconomicEffectDto.Time time(final int clients, final EconomicEffectDto.Assumptions assumptions) {
        final BigDecimal minutes = BigDecimal.valueOf((long) clients * (assumptions.manualMinutesPerClient() - assumptions.systemMinutesPerClient())
                * assumptions.reviewsPerYear());
        final BigDecimal hours = minutes.divide(MINUTES_IN_HOUR, PRECISION);
        return new EconomicEffectDto.Time(hours.setScale(1, RoundingMode.HALF_UP),
                hours.divide(BigDecimal.valueOf(assumptions.fteHoursPerYear()), PRECISION).setScale(1, RoundingMode.HALF_UP),
                money(hours.multiply(assumptions.managerHourCost())));
    }

    /**
     * Масштаб: годовые суммы демо на клиента × клиентов масштаба, сопровождение и окупаемость внедрения.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static EconomicEffectDto.Scale scale(final String code, final String title, final int clients, final int runClients,
                                                 final EconomicEffectDto.Year year, final EconomicEffectDto.Time time,
                                                 final EconomicEffectDto.Assumptions assumptions) {
        final BigDecimal factor = runClients == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(clients).divide(BigDecimal.valueOf(runClients), PRECISION);
        final BigDecimal income = money(year.income().multiply(factor));
        final BigDecimal runCost = money(assumptions.fixedAnnualCost()
                .add(assumptions.annualCostPerClient().multiply(BigDecimal.valueOf(clients))));
        final BigDecimal effect = income.subtract(runCost);
        final BigDecimal payback = effect.signum() <= 0 ? null
                : assumptions.implementationCost().multiply(MONTHS_IN_YEAR).divide(effect, PRECISION).setScale(1, RoundingMode.HALF_UP);
        return new EconomicEffectDto.Scale(code, title, clients, money(year.expectedEad().multiply(factor)), income, runCost, effect,
                time.savedFte().multiply(factor).setScale(1, RoundingMode.HALF_UP), payback);
    }

    private EconomicEffectDto.Assumptions assumptions(final RunParameters parameters) {
        return new EconomicEffectDto.Assumptions(
                orDefault(properties.coreClients(), CORE_CLIENTS), orDefault(properties.segmentClients(), SEGMENT_CLIENTS),
                orDefault(properties.implementationCost(), IMPLEMENTATION_COST),
                orDefault(properties.fixedAnnualCost(), FIXED_ANNUAL_COST), orDefault(properties.annualCostPerClient(), COST_PER_CLIENT),
                orDefault(properties.reviewsPerYear(), REVIEWS_PER_YEAR),
                orDefault(properties.managerHourCost(), MANAGER_HOUR_COST), orDefault(properties.fteHoursPerYear(), FTE_HOURS_PER_YEAR),
                parameters.manualMinutesPerClient(), parameters.systemMinutesPerClient());
    }

    private int clients(final RunRepository.RunRow run) {
        final Map<String, Object> summary = json.readMap(run.summary());
        return summary != null && summary.get("clientCount") instanceof Number number ? number.intValue() : 0;
    }

    private static BigDecimal rate(final BigDecimal requested, final BigDecimal fallback, final String name) {
        if (requested == null) {
            return fallback;
        }
        if (requested.signum() <= 0 || requested.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.validation(name + " - доля от 0 до 1, например 0.16");
        }
        return requested;
    }

    private static <T> T orDefault(final T value, final T fallback) {
        return value != null ? value : fallback;
    }

    private static BigDecimal money(final BigDecimal value) {
        return value.setScale(MONEY, RoundingMode.HALF_UP);
    }

    private record OfferRow(BigDecimal ead, BigDecimal el, int termDays, BigDecimal pdTerm, BigDecimal lgd, BigDecimal tr) {
    }
}
