package ru.smart.factoring.backend.factoring.decision;

import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.factoring.parameter.RunParameters;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Экономика УПД (п. 5.1): простые проценты на сумму финансирования за срок, база 365 дней; суммы - в рублях с копейками.
 * <pre>
 * EL = PD × срок / 365 × LGD × EAD;  фондирование, опер. расходы, маржа = EAD × ставка × срок / 365
 * цена = min(фондирование + EL + опер. расходы + маржа, EAD × рыночная цена × срок / 365)
 * доход банка = цена − фондирование − EL − опер. расходы
 * </pre>
 */
public final class Economics {
    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 6;
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private Economics() {
    }

    /**
     * @param ead      сумма финансирования
     * @param termDays срок финансирования: дней до плановой оплаты + ожидаемая задержка
     * @param pd       PD покупателя годовой
     * @param level    фактический уровень риска: LGD по нему, даже если аванс на ступень ниже (п. 5.1)
     */
    public static Result calculate(final BigDecimal ead, final int termDays, final BigDecimal pd, final RiskLevel level,
                                   final RunParameters parameters) {
        final BigDecimal yearFraction = BigDecimal.valueOf(termDays)
                .divide(BigDecimal.valueOf(parameters.dayCountBasis()), PRECISION);
        final BigDecimal pdTerm = pd.multiply(yearFraction, PRECISION);
        final BigDecimal lgd = parameters.lgd().get(level);
        final BigDecimal el = money(pdTerm.multiply(lgd, PRECISION).multiply(ead, PRECISION));
        final BigDecimal funding = interest(ead, parameters.fundingRate(), yearFraction);
        final BigDecimal opex = interest(ead, parameters.opexRate(), yearFraction);
        final BigDecimal margin = interest(ead, parameters.marginRate(), yearFraction);
        final BigDecimal cap = interest(ead, parameters.marketRate(), yearFraction);
        final BigDecimal cost = funding.add(el).add(opex).add(margin);
        final boolean capApplied = cost.compareTo(cap) > 0;
        final BigDecimal price = capApplied ? cap : cost;
        final BigDecimal income = price.subtract(funding).subtract(el).subtract(opex);
        final BigDecimal priceRate = ead.signum() == 0 || termDays == 0 ? BigDecimal.ZERO
                : price.divide(ead, PRECISION).divide(yearFraction, PRECISION).setScale(RATE_SCALE, RoundingMode.HALF_UP);
        return new Result(ead, termDays, pdTerm.setScale(RATE_SCALE, RoundingMode.HALF_UP), lgd, el, funding, opex, margin, cap,
                price, priceRate, capApplied, income);
    }

    public static BigDecimal money(final BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal interest(final BigDecimal ead, final BigDecimal rate, final BigDecimal yearFraction) {
        return money(ead.multiply(rate, PRECISION).multiply(yearFraction, PRECISION));
    }

    /**
     * Экономика одного УПД при данной сумме финансирования.
     *
     * @param priceRate цена в % годовых (в долях): цена / EAD × 365 / срок
     */
    public record Result(BigDecimal ead, int termDays, BigDecimal pdTerm, BigDecimal lgd, BigDecimal el, BigDecimal funding,
                         BigDecimal opex, BigDecimal margin, BigDecimal priceCap, BigDecimal price, BigDecimal priceRate,
                         boolean capApplied, BigDecimal income) {
    }
}
