package ru.smart.factoring.backend.factoring.reconciliation;

import ru.smart.factoring.backend.factoring.model.Upd;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Блок 2. Сверка оплат: распределение поступлений по УПД пары «клиент - покупатель» (п. 2.1-2.6). Без БД.
 * <p>
 * Платежи - по дате, затем по id источника. Назначение платежа: номер УПД пары как отдельное слово → п. 2.1,
 * иначе номер договора пары → п. 2.2, иначе → п. 2.3. Излишек - на следующий по старшинству открытый УПД того же
 * договора, затем покупателя (п. 2.4). Что не на что отнести - нераспределено (п. 2.6).
 */
public final class Reconciliation {
    private static final Comparator<Upd> SENIORITY = Comparator.comparing(Upd::updDate).thenComparing(Upd::number)
            .thenComparingLong(Upd::id);

    private Reconciliation() {
    }

    public static Outcome reconcile(final List<Upd> upds, final List<Payment> payments) {
        final Map<Long, BigDecimal> outstanding = new HashMap<>();
        final Map<PairKey, List<Upd>> pairs = new HashMap<>();
        for (final Upd upd : upds.stream().sorted(SENIORITY).toList()) {
            outstanding.put(upd.id(), upd.amount());
            pairs.computeIfAbsent(new PairKey(upd.clientId(), upd.buyerInn()), key -> new ArrayList<>()).add(upd);
        }
        final Map<Long, LocalDate> paidDates = new HashMap<>();
        final List<Allocation> allocations = new ArrayList<>();
        final List<Payment> ordered = payments.stream()
                .sorted(Comparator.comparing(Payment::paymentDate).thenComparing(Payment::sourceId))
                .toList();
        for (final Payment payment : ordered) {
            final List<Upd> pair = pairs.getOrDefault(new PairKey(payment.clientId(), payment.payerInn()), List.of());
            final Allocator allocator = new Allocator(payment, outstanding, paidDates, allocations);
            final List<Upd> named = pair.stream().filter(upd -> mentions(payment.purpose(), upd.number())).toList();
            if (!named.isEmpty()) {
                named.forEach(upd -> allocator.allocate(upd, Rule.UPD_NUMBER));
                final Upd first = named.getFirst();
                allocator.fifo(pair.stream().filter(upd -> Objects.equals(upd.contractId(), first.contractId())).toList(),
                        Rule.CARRY_CONTRACT);
                allocator.fifo(pair, Rule.CARRY_BUYER);
            } else {
                final Long contractId = mentionedContract(payment.purpose(), pair);
                if (contractId != null) {
                    allocator.fifo(pair.stream().filter(upd -> contractId.equals(upd.contractId())).toList(), Rule.CONTRACT_FIFO);
                    allocator.fifo(pair, Rule.CARRY_BUYER);
                } else {
                    allocator.fifo(pair, Rule.BUYER_FIFO);
                }
            }
            allocator.rest();
        }
        final Map<Long, Payoff> payoffs = new LinkedHashMap<>();
        for (final Upd upd : upds) {
            final BigDecimal left = outstanding.get(upd.id());
            payoffs.put(upd.id(), new Payoff(upd.amount().subtract(left), left, paidDates.get(upd.id())));
        }
        return new Outcome(allocations, payoffs);
    }

    private static Long mentionedContract(final String purpose, final List<Upd> pair) {
        return pair.stream()
                .filter(upd -> upd.contractId() != null && mentions(purpose, upd.contractNumber()))
                .sorted(Comparator.comparing(Upd::contractDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Upd::contractId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Номер в назначении как отдельное слово: соседние символы - не буква, не цифра и не дефис (U-1001 не находится в U-10011).
     */
    static boolean mentions(final String purpose, final String number) {
        if (purpose == null || number == null || number.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}-])" + Pattern.quote(number) + "(?![\\p{L}\\p{N}-])").matcher(purpose).find();
    }

    /**
     * Правило, по которому платёж отнесён на УПД (payment_allocation.rule).
     */
    public enum Rule {
        UPD_NUMBER,
        CONTRACT_FIFO,
        BUYER_FIFO,
        CARRY_CONTRACT,
        CARRY_BUYER,
        UNALLOCATED
    }

    public record Payment(long id, String sourceId, long clientId, String payerInn, BigDecimal amount, LocalDate paymentDate,
                          String purpose) {
    }

    /**
     * Часть платежа, отнесённая на УПД; updId = null - нераспределённый остаток.
     */
    public record Allocation(long paymentId, Long updId, BigDecimal amount, Rule rule) {
    }

    /**
     * Оплата УПД на дату расчёта.
     *
     * @param paidDate дата платежа, которым УПД оплачен полностью; null - не оплачен полностью
     */
    public record Payoff(BigDecimal paid, BigDecimal outstanding, LocalDate paidDate) {
    }

    public record Outcome(List<Allocation> allocations, Map<Long, Payoff> payoffs) {
    }

    private record PairKey(long clientId, String buyerInn) {
    }

    private static final class Allocator {
        private final Payment payment;
        private final Map<Long, BigDecimal> outstanding;
        private final Map<Long, LocalDate> paidDates;
        private final List<Allocation> allocations;
        private BigDecimal left;

        Allocator(final Payment payment, final Map<Long, BigDecimal> outstanding, final Map<Long, LocalDate> paidDates,
                  final List<Allocation> allocations) {
            this.payment = payment;
            this.outstanding = outstanding;
            this.paidDates = paidDates;
            this.allocations = allocations;
            this.left = payment.amount();
        }

        void fifo(final List<Upd> upds, final Rule rule) {
            for (final Upd upd : upds) {
                if (left.signum() <= 0) {
                    return;
                }
                allocate(upd, rule);
            }
        }

        void allocate(final Upd upd, final Rule rule) {
            final BigDecimal open = outstanding.get(upd.id());
            if (left.signum() <= 0 || open.signum() <= 0) {
                return;
            }
            final BigDecimal amount = left.min(open);
            outstanding.put(upd.id(), open.subtract(amount));
            left = left.subtract(amount);
            allocations.add(new Allocation(payment.id(), upd.id(), amount, rule));
            if (outstanding.get(upd.id()).signum() == 0) {
                paidDates.put(upd.id(), payment.paymentDate());
            }
        }

        void rest() {
            if (left.signum() > 0) {
                allocations.add(new Allocation(payment.id(), null, left, Rule.UNALLOCATED));
            }
        }
    }
}
