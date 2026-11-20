package ru.smart.factoring.backend.factoring.llm;

import ru.smart.factoring.backend.factoring.model.UpdNumber;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Маскирование фактов предложения перед LLM (Р-73). Связка «клиент - покупатель - сумма - срок» составляет банковскую
 * тайну клиента, поэтому во внешнюю LLM уходят метки вместо названия клиента, названия и ИНН покупателя и номера УПД;
 * в ответ LLM настоящие значения подставляет backend. Суммы, даты, уровень риска и причины оценки остаются: без них
 * текст не написать, а по ним одним клиента не узнать.
 * <p>
 * Метки латиницей с номером (CLIENT_1, BUYER_1): русские слова LLM склоняет («ПОКУПАТЕЛЮ»), латинский идентификатор
 * она переписывает как есть. Метка, оставшаяся в ответе после подстановки (искажённая, например «BUYER 1»), - признак
 * испорченного ответа: такой текст менеджеру не показываем ({@link #leftover(String)}).
 */
final class Pseudonyms {
    static final String CLIENT = "CLIENT_1";
    static final String BUYER = "BUYER_1";
    static final String INN = "INN_1";
    static final String UPD = "UPD_1";
    /** «УПД № UPD_1», «УПД UPD_1», «№ UPD_1» или просто «UPD_1» - всё заменяется номером в принятом виде */
    private static final Pattern UPD_MENTION = Pattern.compile("(?:УПД\\s*)?(?:№\\s*)?" + UPD + "(?![0-9])");
    private static final Pattern TOKEN = Pattern.compile("(?<![A-Za-z0-9_])(?:CLIENT|BUYER|INN|UPD)[_ ]?[0-9]+(?![0-9])",
            Pattern.CASE_INSENSITIVE);

    private final ExplanationFacts original;
    private final ExplanationFacts masked;

    private Pseudonyms(final ExplanationFacts original, final ExplanationFacts masked) {
        this.original = original;
        this.masked = masked;
    }

    /**
     * @param mask false - факты уходят как есть (LLM в контуре банка с выключенным маскированием)
     */
    static Pseudonyms of(final ExplanationFacts facts, final boolean mask) {
        if (!mask) {
            return new Pseudonyms(facts, facts);
        }
        // без наименования покупателя (в партиях ML-команды оно «ИНН …») - пустое имя, иначе ИНН ушёл бы в имени
        final boolean noName = facts.buyerName() == null || facts.buyerName().isBlank() || facts.buyerName().startsWith("ИНН ");
        return new Pseudonyms(facts, new ExplanationFacts(CLIENT, noName ? "" : BUYER, INN, UPD, facts.updDate(),
                facts.riskLevel(), facts.pd(), facts.pdOutOf100(), facts.lowData(), facts.lowDataReason(), facts.delayProbable(),
                facts.reducedToLimit(), facts.ead(), facts.price(), facts.priceRate(), facts.termDays(), facts.reasons()));
    }

    /**
     * Факты, которые уходят в LLM.
     */
    ExplanationFacts facts() {
        return masked;
    }

    /**
     * Ответ LLM с настоящими значениями вместо меток.
     */
    String restore(final String answer) {
        if (answer == null || masked == original) {
            return answer;
        }
        final String upd = UPD_MENTION.matcher(answer).replaceAll(Matcher.quoteReplacement(UpdNumber.labelWithNo(original.updNumber())));
        return upd.replace(CLIENT, nullToEmpty(original.clientName()))
                .replace(BUYER, nullToEmpty(original.buyerName()))
                .replace(INN, nullToEmpty(original.buyerInn()));
    }

    /**
     * @return метка, оставшаяся в тексте после {@link #restore(String)}, или null
     */
    static String leftover(final String text) {
        if (text == null) {
            return null;
        }
        final Matcher matcher = TOKEN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }
}
