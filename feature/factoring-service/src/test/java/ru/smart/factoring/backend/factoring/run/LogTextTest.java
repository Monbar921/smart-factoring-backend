package ru.smart.factoring.backend.factoring.run;

import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.api.run.RunStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тексты журнала - для человека (замечания 28.10): дата, сумма, время и статус прогона.
 */
class LogTextTest {

    @Test
    void formatsValuesForPeople() {
        assertThat(LogText.date(LocalDate.of(2025, 12, 30))).isEqualTo("30.12.2025");
        assertThat(LogText.money(new BigDecimal("1056425.37"))).isEqualTo("1 056 425,37 ₽");
        assertThat(LogText.money(new BigDecimal("500000"))).isEqualTo("500 000,00 ₽");
        assertThat(LogText.duration(Duration.ofMillis(850))).isEqualTo("850 мс");
        assertThat(LogText.duration(Duration.ofMillis(16_872))).isEqualTo("16,9 с");
        assertThat(LogText.duration(Duration.ofSeconds(125))).isEqualTo("2 мин 5 с");
        assertThat(LogText.status(RunStatus.DONE)).isEqualTo("выполнен");
        assertThat(LogText.status(RunStatus.DONE_WITH_WARNINGS)).isEqualTo("выполнен с предупреждениями");
    }
}
