package ru.smart.factoring.backend.factoring.decision;

import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.api.common.RiskLevel;
import ru.smart.factoring.backend.factoring.parameter.RunParameters;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Граница уровня относится к следующему, более рискованному уровню - как в модели PD (Р-65, замечания 29.10 П3-4).
 */
class RiskLevelBoundsTest {
    private static final RunParameters PARAMETERS = TestParameters.defaults();

    @Test
    void boundBelongsToRiskierLevel() {
        // границы TestParameters: 1 / 3 / 8 %
        assertThat(PARAMETERS.riskLevel(new BigDecimal("0.0099"))).isEqualTo(RiskLevel.A);
        assertThat(PARAMETERS.riskLevel(new BigDecimal("0.01"))).isEqualTo(RiskLevel.B);
        assertThat(PARAMETERS.riskLevel(new BigDecimal("0.03"))).isEqualTo(RiskLevel.C);
        assertThat(PARAMETERS.riskLevel(new BigDecimal("0.0799"))).isEqualTo(RiskLevel.C);
        assertThat(PARAMETERS.riskLevel(new BigDecimal("0.08"))).isEqualTo(RiskLevel.D);
    }
}
