package ru.smart.factoring.backend.factoring.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.smart.factoring.backend.factoring.reconciliation.Reconciliation;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Категории 1-8 и границы «до 30 / более 30» (п. 3.1-3.8).
 */
class CategoriesTest {

    @ParameterizedTest(name = "частично={0}, дней до срока={1} → категория {2}")
    @CsvSource({
            "false, 0, 1", "false, 30, 1", "false, 31, 2",
            "true, 0, 3", "true, 30, 3", "true, 31, 4",
            "true, -31, 5", "true, -1, 6", "true, -30, 6",
            "false, -31, 7", "false, -1, 8", "false, -30, 8"})
    void categoryByPaymentAndDaysToDue(final boolean partial, final int daysToDue, final int category) {
        assertThat(Categories.category(partial, daysToDue)).isEqualTo(category);
    }

    @Test
    void paidUpdHasNoCategoryAndKeepsDelay() {
        final Upd upd = new Upd(1, 1, 1L, "1", 1L, "Д-1", null, "U-1", LocalDate.parse("2026-06-01"), new BigDecimal("100"), 60);

        final UpdState state = Categories.state(upd,
                new Reconciliation.Payoff(new BigDecimal("100"), BigDecimal.ZERO, LocalDate.parse("2026-08-05")),
                LocalDate.parse("2026-10-24"));

        assertThat(state.paymentStatus()).isEqualTo(UpdState.PaymentStatus.PAID);
        assertThat(state.category()).isNull();
        assertThat(state.plannedDueDate()).isEqualTo(LocalDate.parse("2026-07-31"));
        assertThat(state.delayDays()).isEqualTo(5);
    }
}
