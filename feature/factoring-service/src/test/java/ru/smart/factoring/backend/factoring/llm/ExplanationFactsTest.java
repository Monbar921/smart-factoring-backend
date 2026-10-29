package ru.smart.factoring.backend.factoring.llm;

import org.junit.jupiter.api.Test;
import ru.smart.factoring.backend.factoring.ml.ModelClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Факты для LLM (Р-61): номер УПД без повтора слова «УПД», пустые причины оценки названы прямо, а не пустым списком.
 */
class ExplanationFactsTest {

    @Test
    void updNumberWithWordUpdIsNotRepeated() {
        assertThat(facts("УПД-0280", List.of()).describe()).contains("УПД: УПД-0280 от 22.12.2025").doesNotContain("№ УПД");
        assertThat(facts("U-1004", List.of()).describe()).contains("УПД: № U-1004 от 22.12.2025");
    }

    @Test
    void missingReasonsAreSaidPlainly() {
        final ExplanationFacts facts = facts("УПД-0280", List.of());

        assertThat(facts.describe()).contains("Главные причины оценки покупателя: не переданы");
        assertThat(ExplainerService.templateReasons(facts))
                .startsWith("Покупатель ИНН 9725077448: риск B, средний риск. Около 2 из 100 таких компаний за год не смогут")
                .doesNotContain("1,58")
                .contains("не переданы")
                .doesNotContain("Снижают риск", "Повышают риск");
    }

    @Test
    void reasonsAreListedWithDirection() {
        final ExplanationFacts facts = facts("U-1004", List.of(
                new ModelClient.Reason("equity_ratio", "Доля собственного капитала", new BigDecimal("0.4196"), "DECREASES_RISK", null)));

        assertThat(facts.describe()).contains("- Доля собственного капитала = 0.4196: снижает риск");
    }

    private static ExplanationFacts facts(final String number, final List<ModelClient.Reason> reasons) {
        return new ExplanationFacts("Клиент 0008", "ИНН 9725077448", "9725077448", number, "22.12.2025", "B", "1,58%",
                ExplanationFacts.outOf100(new BigDecimal("0.0158")), false, null, true,
                false, "5 747 121", "102 082", "20,7%", 40, reasons);
    }
}
