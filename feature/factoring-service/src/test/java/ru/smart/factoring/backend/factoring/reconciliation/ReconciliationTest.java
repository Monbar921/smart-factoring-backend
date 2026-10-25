package ru.smart.factoring.backend.factoring.reconciliation;

import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.factoring.model.Upd;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила сверки оплат п. 2.1-2.6.
 */
class ReconciliationTest {
    private static final long CLIENT = 1;
    private static final String BUYER = "7720000002";
    private static final long CONTRACT_A = 10;
    private static final long CONTRACT_B = 20;

    @Test
    void allocatesByUpdNumber() {
        final List<Upd> upds = List.of(upd(1, CONTRACT_A, "U-1", "2026-09-01", 100), upd(2, CONTRACT_A, "U-2", "2026-09-10", 100));

        final Reconciliation.Outcome outcome = Reconciliation.reconcile(upds, List.of(payment(1, "Оплата по УПД № U-2 от 10.09", 100)));

        assertThat(outcome.allocations()).containsExactly(allocation(1, 2L, 100, Reconciliation.Rule.UPD_NUMBER));
        assertThat(outcome.payoffs().get(2L).paidDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(outcome.payoffs().get(1L).outstanding()).isEqualByComparingTo("100");
    }

    @Test
    void doesNotMatchNumberInsideAnotherNumber() {
        assertThat(Reconciliation.mentions("оплата по УПД U-10011", "U-1001")).isFalse();
        assertThat(Reconciliation.mentions("оплата по УПД U-1001.", "U-1001")).isTrue();
        assertThat(Reconciliation.mentions("по договору №Д-101 от", "Д-101")).isTrue();
    }

    @Test
    void allocatesByContractOldestFirst() {
        final List<Upd> upds = List.of(
                upd(1, CONTRACT_B, "U-1", "2026-08-01", 100),
                upd(2, CONTRACT_A, "U-2", "2026-09-01", 100),
                upd(3, CONTRACT_A, "U-3", "2026-08-15", 100));

        final Reconciliation.Outcome outcome = Reconciliation.reconcile(upds, List.of(payment(1, "Оплата по договору Д-10", 150)));

        assertThat(outcome.allocations()).containsExactly(
                allocation(1, 3L, 100, Reconciliation.Rule.CONTRACT_FIFO),
                allocation(1, 2L, 50, Reconciliation.Rule.CONTRACT_FIFO));
    }

    @Test
    void allocatesWithoutNumbersByBuyerOldestFirst() {
        final List<Upd> upds = List.of(upd(1, CONTRACT_B, "U-1", "2026-08-01", 100), upd(2, CONTRACT_A, "U-2", "2026-07-01", 100));

        final Reconciliation.Outcome outcome = Reconciliation.reconcile(upds, List.of(payment(1, "оплата по договору", 120)));

        assertThat(outcome.allocations()).containsExactly(
                allocation(1, 2L, 100, Reconciliation.Rule.BUYER_FIFO),
                allocation(1, 1L, 20, Reconciliation.Rule.BUYER_FIFO));
    }

    @Test
    void carriesOverpaymentToContractThenBuyer() {
        final List<Upd> upds = List.of(
                upd(1, CONTRACT_A, "U-1", "2026-09-01", 100),
                upd(2, CONTRACT_A, "U-2", "2026-09-05", 30),
                upd(3, CONTRACT_B, "U-3", "2026-08-01", 100));

        final Reconciliation.Outcome outcome = Reconciliation.reconcile(upds, List.of(payment(1, "Оплата по УПД U-1", 150)));

        assertThat(outcome.allocations()).containsExactly(
                allocation(1, 1L, 100, Reconciliation.Rule.UPD_NUMBER),
                allocation(1, 2L, 30, Reconciliation.Rule.CARRY_CONTRACT),
                allocation(1, 3L, 20, Reconciliation.Rule.CARRY_BUYER));
    }

    @Test
    void leavesUnallocatedWhenPairHasNoOpenUpds() {
        final List<Upd> upds = List.of(upd(1, CONTRACT_A, "U-1", "2026-09-01", 100));

        final Reconciliation.Outcome outcome = Reconciliation.reconcile(upds, List.of(
                payment(1, "Оплата по УПД U-1", 100),
                payment(2, "Оплата по УПД U-1", 40),
                new Reconciliation.Payment(3, "P-3", CLIENT, "0000000000", new BigDecimal("70"), LocalDate.parse("2026-10-02"), null)));

        assertThat(outcome.allocations()).containsExactly(
                allocation(1, 1L, 100, Reconciliation.Rule.UPD_NUMBER),
                allocation(2, null, 40, Reconciliation.Rule.UNALLOCATED),
                allocation(3, null, 70, Reconciliation.Rule.UNALLOCATED));
    }

    @Test
    void processesPaymentsInDateOrder() {
        final List<Upd> upds = List.of(upd(1, CONTRACT_A, "U-1", "2026-09-01", 100), upd(2, CONTRACT_A, "U-2", "2026-09-02", 100));
        final Reconciliation.Payment later = new Reconciliation.Payment(1, "P-1", CLIENT, BUYER, new BigDecimal("100"),
                LocalDate.parse("2026-10-05"), "оплата");
        final Reconciliation.Payment earlier = new Reconciliation.Payment(2, "P-2", CLIENT, BUYER, new BigDecimal("100"),
                LocalDate.parse("2026-10-01"), "Оплата по УПД U-1");

        final Reconciliation.Outcome outcome = Reconciliation.reconcile(upds, List.of(later, earlier));

        assertThat(outcome.allocations()).containsExactly(
                allocation(2, 1L, 100, Reconciliation.Rule.UPD_NUMBER),
                allocation(1, 2L, 100, Reconciliation.Rule.BUYER_FIFO));
    }

    private static Upd upd(final long id, final long contractId, final String number, final String date, final int amount) {
        return new Upd(id, CLIENT, 5L, BUYER, contractId, contractId == CONTRACT_A ? "Д-10" : "Д-20", LocalDate.parse("2026-01-01"),
                number, LocalDate.parse(date), new BigDecimal(amount), 30);
    }

    private static Reconciliation.Payment payment(final long id, final String purpose, final int amount) {
        return new Reconciliation.Payment(id, "P-" + id, CLIENT, BUYER, new BigDecimal(amount), LocalDate.parse("2026-10-01"), purpose);
    }

    private static Reconciliation.Allocation allocation(final long paymentId, final Long updId, final int amount,
                                                        final Reconciliation.Rule rule) {
        return new Reconciliation.Allocation(paymentId, updId, new BigDecimal(amount), rule);
    }
}
