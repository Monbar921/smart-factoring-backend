package ru.smart.factoring.backend.factoring.decision;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.api.common.RiskLevel;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Экономика УПД на примерах BRD п. 5.1 (BRD округляет до рубля).
 */
class EconomicsTest {
    private static final Offset<BigDecimal> RUBLE = Offset.offset(new BigDecimal("1"));

    @Test
    void bExampleWithoutCap() {
        final Economics.Result result = Economics.calculate(new BigDecimal("4250000.00"), 46, new BigDecimal("0.03"), RiskLevel.B,
                TestParameters.defaults());

        assertThat(result.el()).isCloseTo(new BigDecimal("7231"), RUBLE);
        assertThat(result.funding()).isCloseTo(new BigDecimal("85699"), RUBLE);
        assertThat(result.opex()).isCloseTo(new BigDecimal("8034"), RUBLE);
        assertThat(result.margin()).isCloseTo(new BigDecimal("16068"), RUBLE);
        assertThat(result.price()).isCloseTo(new BigDecimal("117032"), RUBLE);
        assertThat(result.priceCap()).isCloseTo(new BigDecimal("128548"), RUBLE);
        assertThat(result.capApplied()).isFalse();
        assertThat(result.income()).isCloseTo(new BigDecimal("16068"), RUBLE);
        assertThat(result.priceRate()).isCloseTo(new BigDecimal("0.2185"), Offset.offset(new BigDecimal("0.0001")));
    }

    @Test
    void cExampleWithCap() {
        final Economics.Result result = Economics.calculate(new BigDecimal("3750000.00"), 46, new BigDecimal("0.08"), RiskLevel.C,
                TestParameters.defaults());

        assertThat(result.capApplied()).isTrue();
        assertThat(result.price()).isCloseTo(new BigDecimal("113425"), RUBLE);
        assertThat(result.income()).isCloseTo(new BigDecimal("11815"), RUBLE);
        assertThat(result.margin()).isCloseTo(new BigDecimal("14178"), RUBLE);
        assertThat(result.priceRate()).isEqualByComparingTo("0.240000");
    }
}
