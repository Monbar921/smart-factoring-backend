package ru.smart.factoring.backend.factoring.llm;

import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.factoring.ml.ModelClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Маскирование фактов перед LLM (Р-73): в LLM не уходят названия, ИНН и номер УПД, в ответ они возвращаются.
 */
class PseudonymsTest {
    private static final String CLIENT = "ООО \"Вектор-Строй\"";
    private static final String BUYER = "АО \"Надёжный партнёр\"";
    private static final String INN = "7701234567";

    @Test
    void factsSentToLlmHaveNoNamesInnOrDocumentNumber() {
        final ExplanationFacts facts = facts(BUYER, "U-1004");
        final String sent = Pseudonyms.of(facts, true).facts().describe();

        assertThat(sent)
                .doesNotContain("Вектор", "Надёжный", INN, "U-1004")
                .contains("Клиент: CLIENT_1", "Покупатель: BUYER_1, ИНН INN_1", "УПД: № UPD_1 от 22.12.2025")
                // суммы, сроки и причины нужны для текста и остаются
                .contains("5 747 121", "срок 40 дн.", "Доля собственного капитала = 0.4196");
    }

    @Test
    void buyerWithoutNameIsSentByInnTokenOnly() {
        final String sent = Pseudonyms.of(facts("ИНН " + INN, "U-1004"), true).facts().describe();

        assertThat(sent).contains("Покупатель: ИНН INN_1").doesNotContain(INN);
    }

    @Test
    void answerGetsRealValuesBack() {
        final Pseudonyms pseudonyms = Pseudonyms.of(facts(BUYER, "U-1004"), true);

        assertThat(pseudonyms.restore("Клиенту CLIENT_1: по отгрузке покупателю BUYER_1 (ИНН INN_1, УПД № UPD_1 от 22.12.2025)"))
                .isEqualTo("Клиенту " + CLIENT + ": по отгрузке покупателю " + BUYER + " (ИНН " + INN + ", УПД № U-1004 от 22.12.2025)");
    }

    @Test
    void documentNumberIsWrittenTheAcceptedWay() {
        // номер «УПД-0280» уже со словом «УПД»: «УПД № УПД-0280» было бы повтором (П2-4)
        final Pseudonyms withWord = Pseudonyms.of(facts(BUYER, "УПД-0280"), true);
        assertThat(withWord.restore("по УПД № UPD_1 от 22.12.2025")).isEqualTo("по УПД-0280 от 22.12.2025");
        assertThat(withWord.restore("по UPD_1 от 22.12.2025")).isEqualTo("по УПД-0280 от 22.12.2025");

        final Pseudonyms plain = Pseudonyms.of(facts(BUYER, "1187"), true);
        assertThat(plain.restore("(UPD_1 от 22.12.2025)")).isEqualTo("(УПД № 1187 от 22.12.2025)");
    }

    @Test
    void mangledTokenIsFound() {
        final Pseudonyms pseudonyms = Pseudonyms.of(facts(BUYER, "U-1004"), true);

        assertThat(Pseudonyms.leftover(pseudonyms.restore("У покупателя Buyer 1 риск B"))).isEqualTo("Buyer 1");
        assertThat(Pseudonyms.leftover(pseudonyms.restore("У покупателя BUYER_2 риск B"))).isEqualTo("BUYER_2");
        assertThat(Pseudonyms.leftover(pseudonyms.restore("У покупателя BUYER_1 риск B"))).isNull();
    }

    @Test
    void withoutMaskingFactsGoAsIs() {
        final ExplanationFacts facts = facts(BUYER, "U-1004");
        final Pseudonyms pseudonyms = Pseudonyms.of(facts, false);

        assertThat(pseudonyms.facts().describe()).isEqualTo(facts.describe());
        assertThat(pseudonyms.restore("текст BUYER_1")).isEqualTo("текст BUYER_1");
    }

    private static ExplanationFacts facts(final String buyerName, final String number) {
        return new ExplanationFacts(CLIENT, buyerName, INN, number, "22.12.2025", "B", "1,58%",
                ExplanationFacts.outOf100(new BigDecimal("0.0158")), false, null, false, false, "5 747 121", "102 082", "20,7%", 40,
                List.of(new ModelClient.Reason("equity_ratio", "Доля собственного капитала", new BigDecimal("0.4196"),
                        "DECREASES_RISK", null)));
    }
}
