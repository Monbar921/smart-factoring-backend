package ru.smart.factoring.backend.factoring.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Состояние УПД на дату расчёта после блоков 2-3 (upd_state).
 *
 * @param daysToDue  плановая дата оплаты минус дата расчёта: 0 и больше - срок не наступил, меньше 0 - просрочка
 * @param paidDate   дата полной оплаты; null - не оплачен полностью
 * @param delayDays  для оплаченных: дата оплаты минус плановая дата
 * @param category   1-8 для открытых, null для оплаченных
 */
public record UpdState(Upd upd,
                       LocalDate plannedDueDate,
                       int daysToDue,
                       BigDecimal paid,
                       BigDecimal outstanding,
                       PaymentStatus paymentStatus,
                       LocalDate paidDate,
                       Integer delayDays,
                       Integer category) {

    public boolean open() {
        return paymentStatus != PaymentStatus.PAID;
    }

    /**
     * Категории 1-4: срок не наступил, факторинг возможен (п. 3.9).
     */
    public boolean financeable() {
        return category != null && category <= Categories.LAST_FINANCEABLE;
    }

    public boolean overdue() {
        return category != null && category > Categories.LAST_FINANCEABLE;
    }

    public enum PaymentStatus {
        UNPAID,
        PARTIAL,
        PAID
    }
}
