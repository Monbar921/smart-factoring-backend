package ru.smart.factoring.backend.factoring.llm;

import ru.smart.factoring.backend.factoring.decision.DecisionEngine;
import ru.smart.factoring.backend.factoring.decision.Economics;
import ru.smart.factoring.backend.factoring.ml.ModelClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Факты предложения для текстов LLM: без персональных данных (НФР-10), только то, что видит менеджер в карточке.
 */
public record ExplanationFacts(String clientName, String buyerName, String buyerInn, String updNumber, String updDate,
                               String riskLevel, String pd, boolean lowData, boolean delayProbable, boolean reducedToLimit,
                               String ead, String price, String priceRate, int termDays, List<ModelClient.Reason> reasons) {
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** даты - как пишут в письмах: 29.12.2025, не ISO */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static ExplanationFacts of(final DecisionEngine.Decision decision, final String buyerName) {
        final Economics.Result economics = decision.finalEconomics();
        return new ExplanationFacts(decision.client().name(), buyerName, decision.state().upd().buyerInn(),
                decision.state().upd().number(), decision.state().upd().updDate().format(DATE), decision.buyer().riskLevel().name(),
                percent(decision.buyer().pd()), decision.buyer().lowData(), decision.delayProbable(), decision.reducedToLimit(),
                money(economics.ead()), money(economics.price()), percent(economics.priceRate()), economics.termDays(),
                decision.buyer().reasons() != null ? decision.buyer().reasons() : List.of());
    }

    /**
     * Строка фактов для промпта и ключа кеша.
     */
    public String describe() {
        final StringBuilder text = new StringBuilder()
                .append("Клиент: ").append(clientName).append('\n')
                .append("Покупатель: ").append(buyerTitle()).append('\n')
                .append("УПД: № ").append(updNumber).append(" от ").append(updDate).append('\n')
                .append("Уровень риска покупателя: ").append(riskLevel).append(", PD ").append(pd)
                .append(lowData ? " (мало данных: базовый PD по отрасли и размеру)" : "").append('\n')
                .append("Сумма финансирования: ").append(ead).append(" руб.")
                .append(reducedToLimit ? " (уменьшена до остатка лимита на покупателя)" : "").append('\n')
                .append("Цена: ").append(price).append(" руб., ").append(priceRate).append(" годовых, срок ").append(termDays)
                .append(" дн.").append(delayProbable ? "; вероятна задержка оплаты, аванс на ступень ниже" : "").append('\n')
                .append("Главные причины оценки покупателя:\n");
        reasons.forEach(reason -> text.append("- ").append(reason.title())
                .append(reason.value() != null ? " = " + reason.value().stripTrailingZeros().toPlainString() : "")
                .append(": ").append("INCREASES_RISK".equals(reason.direction()) ? "повышает риск" : "снижает риск").append('\n'));
        return text.toString();
    }

    /**
     * Покупатель для текста: «ООО Ромашка, ИНН …»; без наименования (в партиях ML-команды оно «ИНН …») - только ИНН.
     */
    public String buyerTitle() {
        final boolean noName = buyerName == null || buyerName.isBlank() || buyerName.startsWith("ИНН ");
        return noName ? "ИНН " + buyerInn : buyerName + ", ИНН " + buyerInn;
    }

    static String money(final BigDecimal value) {
        final DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(RU));
        return format.format(value.setScale(0, RoundingMode.HALF_UP));
    }

    static String percent(final BigDecimal fraction) {
        final DecimalFormat format = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(RU));
        return format.format(fraction.multiply(HUNDRED)) + "%";
    }
}
