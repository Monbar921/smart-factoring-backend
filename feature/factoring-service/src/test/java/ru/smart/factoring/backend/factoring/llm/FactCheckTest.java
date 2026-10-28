package ru.smart.factoring.backend.factoring.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Домыслы в ответах GigaChat, найденные на стенде (Р-59), ловятся; то же слово из входных фактов - допустимо.
 */
class FactCheckTest {
    private static final String FACTS = """
            Покупатель: ИНН 6685084987
            УПД: № УПД-0255 от 22.12.2025
            Главные причины оценки покупателя:
            - Коэффициент автономии = 0.42: снижает риск
            """;

    @Test
    void catchesInventedAssessmentsAndPromises() {
        assertThat(FactCheck.unsupported("Риски минимальны благодаря положительной репутации покупателя.", FACTS)).isEqualTo("репутац");
        assertThat(FactCheck.unsupported("Влияют на платежеспособность компании-покупателя.", FACTS)).isEqualTo("платежеспособн");
        assertThat(FactCheck.unsupported("Сумма будет выплачена вам немедленно.", FACTS)).isEqualTo("немедленно");
        assertThat(FactCheck.unsupported("Готовы профинансировать уже сегодня под фиксированную ставку.", FACTS)).isNotNull();
        assertThat(FactCheck.unsupported("Деньги поступят сразу после подписания договора.", FACTS)).isEqualTo("после подписания");
    }

    @Test
    void acceptsAnswerBasedOnFacts() {
        assertThat(FactCheck.unsupported("Банк готов профинансировать поставку покупателю ИНН 6685084987 по УПД-0255 от 22.12.2025.",
                FACTS)).isNull();
        assertThat(FactCheck.unsupported("Риск снижает высокий коэффициент автономии.", FACTS)).isNull();
        // тема есть среди причин оценки - о ней писать можно
        assertThat(FactCheck.unsupported("Кредитная нагрузка повышает риск.", FACTS + "- Кредитная нагрузка = 3.1: повышает риск\n"))
                .isNull();
    }
}
