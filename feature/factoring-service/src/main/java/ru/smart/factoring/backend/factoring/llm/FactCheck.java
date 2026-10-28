package ru.smart.factoring.backend.factoring.llm;

import java.util.List;
import java.util.Locale;

/**
 * Проверка ответа LLM на домыслы (Р-59): одними промптами GigaChat не удержать - на стенде он писал
 * о «положительной репутации» покупателя и обещал выплату «уже сегодня». Тема допустима, только если она есть во входных фактах.
 */
final class FactCheck {
    /**
     * Оценки клиента и покупателя, которых система не делает, и обещания сроков и условий, которых нет в предложении.
     * Корни слов - чтобы ловить любые формы.
     */
    private static final List<String> UNSUPPORTED = List.of(
            "репутац", "платежеспособн", "платёжеспособн", "финансовой стабильн", "финансовая стабильн", "финансовую стабильн",
            "финансовое состояни", "финансового состояни", "финансовом состояни", "кредитн", "надёжн", "надежн", "благонадёжн",
            "добросовестн", "немедленно", "сегодня", "после подписания", "фиксированн", "гарантир");

    private FactCheck() {
    }

    /**
     * @return первый найденный домысел (корень слова) или null - ответ опирается только на факты
     */
    static String unsupported(final String answer, final String facts) {
        if (answer == null) {
            return null;
        }
        final String text = answer.toLowerCase(Locale.ROOT);
        final String input = facts.toLowerCase(Locale.ROOT);
        return UNSUPPORTED.stream()
                .filter(text::contains)
                .filter(stem -> !input.contains(stem))
                .findFirst()
                .orElse(null);
    }
}
