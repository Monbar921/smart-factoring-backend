package ru.smart.factoring.backend.factoring.model;

import ru.smart.factoring.backend.factoring.reconciliation.Reconciliation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Блок 3. Категории дебиторки 1-8 (п. 3.1-3.8). Без БД.
 * <pre>
 *                        до срока ≤ 30   до срока > 30   просрочка > 30   просрочка ≤ 30
 * полностью неоплаченный       1               2                7                8
 * оплачен частично             3               4                5                6
 * </pre>
 * Дней до срока = плановая дата − дата расчёта; 0 - срок не наступил. «До 30» до срока - от 0 до 30 включительно,
 * просрочка «до 30» - от 1 до 30 дней.
 */
public final class Categories {
    public static final int LAST_FINANCEABLE = 4;
    /**
     * Категории с просрочкой более 30 дней: сигнал в риск-подразделение (п. 3.10 в).
     */
    public static final int PARTIAL_OVERDUE_OVER_30 = 5;
    public static final int UNPAID_OVERDUE_OVER_30 = 7;
    private static final int BOUNDARY_DAYS = 30;

    private Categories() {
    }

    public static UpdState state(final Upd upd, final Reconciliation.Payoff payoff, final LocalDate calcDate) {
        final LocalDate due = upd.plannedDueDate();
        final int daysToDue = (int) ChronoUnit.DAYS.between(calcDate, due);
        if (payoff.outstanding().signum() == 0) {
            final int delay = (int) ChronoUnit.DAYS.between(due, payoff.paidDate());
            return new UpdState(upd, due, daysToDue, payoff.paid(), payoff.outstanding(), UpdState.PaymentStatus.PAID,
                    payoff.paidDate(), delay, null);
        }
        final boolean partial = payoff.paid().signum() > 0;
        return new UpdState(upd, due, daysToDue, payoff.paid(), payoff.outstanding(),
                partial ? UpdState.PaymentStatus.PARTIAL : UpdState.PaymentStatus.UNPAID, null, null,
                category(partial, daysToDue));
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    public static int category(final boolean partial, final int daysToDue) {
        if (daysToDue >= 0) {
            final boolean near = daysToDue <= BOUNDARY_DAYS;
            if (partial) {
                return near ? 3 : 4;
            }
            return near ? 1 : 2;
        }
        final boolean shortOverdue = -daysToDue <= BOUNDARY_DAYS;
        if (partial) {
            return shortOverdue ? 6 : PARTIAL_OVERDUE_OVER_30;
        }
        return shortOverdue ? 8 : UNPAID_OVERDUE_OVER_30;
    }
}
