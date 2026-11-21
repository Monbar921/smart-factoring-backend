package ru.smart.factoring.backend.factoring.monitoring;

import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.api.monitoring.MonitoringDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PSI и уровни по границам эталона (Р-87).
 */
class MonitoringServiceTest {
    private static final Map<String, BigDecimal> TRAINING = Map.of(
            "A", new BigDecimal("0.625927"), "B", new BigDecimal("0.234825"),
            "C", new BigDecimal("0.095434"), "D", new BigDecimal("0.043814"));

    @Test
    void sameDistributionIsStable() {
        final MonitoringDto.Psi psi = MonitoringService.psi(TRAINING, TRAINING, "эталон", null, 500);

        assertThat(psi.value()).isZero();
        assertThat(psi.status()).isEqualTo("STABLE");
    }

    @Test
    void riskierPortfolioIsShift() {
        // вдвое больше покупателей C и D: портфель заметно рискованнее выборки разработки
        final Map<String, BigDecimal> current = Map.of("A", new BigDecimal("0.40"), "B", new BigDecimal("0.25"),
                "C", new BigDecimal("0.20"), "D", new BigDecimal("0.15"));

        final MonitoringDto.Psi psi = MonitoringService.psi(current, TRAINING, "эталон", null, 500);

        // Σ (a − e) · ln(a / e) по A–D
        assertThat(psi.value().doubleValue()).isCloseTo(0.3102, org.assertj.core.data.Offset.offset(0.001));
        assertThat(psi.status()).isEqualTo("SHIFT");
    }

    @Test
    void thresholds() {
        assertThat(MonitoringService.status(new BigDecimal("0.0999"))).isEqualTo("STABLE");
        assertThat(MonitoringService.status(new BigDecimal("0.1"))).isEqualTo("WATCH");
        assertThat(MonitoringService.status(new BigDecimal("0.25"))).isEqualTo("SHIFT");
    }

    @Test
    void emptyCurrentHasNoPsi() {
        assertThat(MonitoringService.psi(Map.of(), TRAINING, "эталон", null, 0)).isNull();
    }

    @Test
    void levelByReferenceCuts() {
        final List<BigDecimal> cuts = List.of(new BigDecimal("0.004"), new BigDecimal("0.015"), new BigDecimal("0.06"));

        assertThat(MonitoringService.level(new BigDecimal("0.0039"), cuts)).isEqualTo("A");
        assertThat(MonitoringService.level(new BigDecimal("0.004"), cuts)).isEqualTo("B");
        assertThat(MonitoringService.level(new BigDecimal("0.0599"), cuts)).isEqualTo("C");
        assertThat(MonitoringService.level(new BigDecimal("0.2"), cuts)).isEqualTo("D");
    }
}
