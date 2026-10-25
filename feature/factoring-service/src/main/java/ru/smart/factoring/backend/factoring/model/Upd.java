package ru.smart.factoring.backend.factoring.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * УПД прогона с данными договора.
 *
 * @param buyerId      null - покупателя нет в реестре (п. 1.4)
 * @param deferralDays срок отсрочки из договора; плановая дата оплаты = дата отгрузки + отсрочка (блок 3)
 */
public record Upd(long id,
                  long clientId,
                  Long buyerId,
                  String buyerInn,
                  Long contractId,
                  String contractNumber,
                  LocalDate contractDate,
                  String number,
                  LocalDate updDate,
                  BigDecimal amount,
                  int deferralDays) {

    public LocalDate plannedDueDate() {
        return updDate.plusDays(deferralDays);
    }
}
