package ru.smart.factoring.backend.factoring.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ru.smart.factoring.backend.factoring.decision.DecisionEngine;
import ru.smart.factoring.backend.factoring.decision.Explainer;
import ru.smart.factoring.backend.factoring.model.UpdNumber;
import ru.smart.factoring.backend.factoring.decision.Explainer.Source;
import ru.smart.factoring.backend.factoring.ml.ModelClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Тексты карточки предложения (п. 5.3, Р-12):
 * <ul>
 *     <li>stub - шаблонные тексты по фактам предложения, без LLM (по умолчанию, пока нет ключа GigaChat);</li>
 *     <li>gigachat - GigaChat ({@link LlmClient}) по промптам из classpath:llm/*.txt; ответы кешируются по хешу входа (Р-7),
 *     чтобы повторный прогон не обращался к LLM и давал те же карточки;</li>
 *     <li>off, а также сбой LLM или ответ, заменённый цензурой GigaChat, - причины шаблоном, черновика нет:
 *     предложение не блокируется.</li>
 * </ul>
 */
@Slf4j
@Service
public class ExplainerService implements Explainer {
    /**
     * Версия в «версиях моделей» прогона и в ключе кеша. Тексты промптов входят в ключ кеша сами (Р-60), версию меняем,
     * когда меняется формат фактов или проверка ответов.
     */
    private static final String PROMPT_VERSION = "v3";
    /** уровни риска словами - как в UI (замечания 28.10, П2-1) */
    private static final Map<String, String> RISK_WORDS = Map.of("A", "надёжный покупатель", "B", "средний риск",
            "C", "повышенный риск", "D", "высокий риск");
    private static final String RETRY_NOTE = """


            В прошлый раз ответ содержал сведения, которых нет во входе. Пиши строго по переданным фактам: никаких оценок
            репутации, платёжеспособности, финансового состояния и кредитной истории, никаких обещаний сроков выплаты
            и условий, которых нет во входе.""";
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 512;
    /** finish_reason GigaChat: ответ заменён шаблонной фразой из-за цензуры */
    private static final String BLACKLIST = "blacklist";

    private final LlmProperties properties;
    private final NamedParameterJdbcTemplate jdbc;
    private final LlmClient llm;
    private final String reasonsPrompt;
    private final String draftPrompt;
    private final Map<Long, String> buyerNames = new ConcurrentHashMap<>();

    public ExplainerService(final LlmProperties properties, final NamedParameterJdbcTemplate jdbc, final LlmClient llm) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.llm = llm;
        this.reasonsPrompt = resource("llm/reasons-prompt.txt");
        this.draftPrompt = resource("llm/draft-prompt.txt");
    }

    @Override
    public String provider() {
        return switch (mode()) {
            case "gigachat" -> "gigachat:" + llm.model() + ":" + PROMPT_VERSION;
            case "off" -> "off";
            default -> "stub";
        };
    }

    @Override
    public String textsVersion() {
        return provider() + ":" + hash(reasonsPrompt + "\n" + draftPrompt);
    }

    @Override
    @SuppressWarnings("checkstyle:IllegalCatch")
    public Texts explain(final DecisionEngine.Decision decision) {
        final ExplanationFacts facts = ExplanationFacts.of(decision, buyerName(decision.state().upd().buyerId()));
        return switch (mode()) {
            case "gigachat" -> {
                // в ключе - и тексты промптов: правка llm/*.txt сама сбрасывает кеш, без ручной смены PROMPT_VERSION
                final String hash = hash(provider() + "\n" + reasonsPrompt + "\n" + draftPrompt + "\n" + facts.describe());
                final Texts cached = cached(hash);
                if (cached != null) {
                    yield cached;
                }
                try {
                    // без показателей покупателя (PD из партии данных) LLM нечего объяснять: на месте пустого списка она
                    // подставляла пометки предложения - причины шаблоном, черновик клиенту по-прежнему от LLM (Р-61)
                    final String reasons = facts.reasons().isEmpty() ? templateReasons(facts) : checked(reasonsPrompt, facts);
                    final String draft = checked(draftPrompt, facts);
                    if (reasons == null || draft == null) {
                        // LLM дважды ответила с домыслами: такой текст менеджеру не показываем, вместо него - шаблон
                        yield Texts.of(reasons != null ? reasons : templateReasons(facts), draft != null ? draft : templateDraft(facts),
                                Source.TEMPLATE);
                    }
                    final Texts texts = Texts.of(reasons, draft, Source.LLM);
                    cache(hash, texts);
                    yield texts;
                } catch (RuntimeException e) {
                    log.warn("GigaChat недоступен, карточка {} без черновика: {}", facts.updNumber(), e.getMessage());
                    yield new Texts(templateReasons(facts), null, Source.FAILED, e.getMessage());
                }
            }
            case "off" -> Texts.of(templateReasons(facts), null, Source.TEMPLATE);
            default -> Texts.of(templateReasons(facts), templateDraft(facts), Source.TEMPLATE);
        };
    }

    /**
     * Ответ LLM, проверенный на домыслы (Р-59): с домыслом - один повтор с напоминанием, снова с домыслом - null (будет шаблон).
     */
    private String checked(final String systemPrompt, final ExplanationFacts facts) {
        final String input = facts.describe();
        final String first = complete(systemPrompt, input);
        final String found = FactCheck.unsupported(first, input);
        if (found == null) {
            return first;
        }
        log.info("Карточка {}: в ответе LLM домысел «{}…», запрос повторён", facts.updNumber(), found);
        final String second = complete(systemPrompt + RETRY_NOTE, input);
        final String again = FactCheck.unsupported(second, input);
        if (again == null) {
            return second;
        }
        log.warn("Карточка {}: в ответе LLM снова домысел «{}…» - текст шаблоном", facts.updNumber(), again);
        return null;
    }

    private String complete(final String systemPrompt, final String facts) {
        final LlmClient.Completion completion = llm.complete(new LlmClient.CompletionRequest(
                List.of(new LlmClient.Message("system", systemPrompt), new LlmClient.Message("user", facts)), TEMPERATURE, MAX_TOKENS));
        if (BLACKLIST.equals(completion.finishReason())) {
            throw new IllegalStateException("ответ заменён цензурой GigaChat");
        }
        return completion.content();
    }

    private String mode() {
        return properties.mode() == null ? "stub" : properties.mode().toLowerCase(Locale.ROOT);
    }

    static String templateReasons(final ExplanationFacts facts) {
        // риск простым языком, без процента PD (замечания 28.10, П2-1.2): «B, средний риск. Около 2 из 100 …»
        final StringBuilder text = new StringBuilder("Покупатель ").append(facts.buyerTitle()).append(": риск ")
                .append(facts.riskLevel()).append(", ").append(RISK_WORDS.getOrDefault(facts.riskLevel(), facts.riskLevel())).append('.');
        if (facts.pdOutOf100() != null) {
            text.append(' ').append(riskOutOf100Sentence(facts.pdOutOf100()));
        }
        if (facts.lowData()) {
            text.append(" Данных мало")
                    .append(facts.lowDataReason() != null ? " (" + ExplanationFacts.lowDataReasonText(facts.lowDataReason()) + ")" : "")
                    .append(": оценка ориентировочная, по отрасли и причине нехватки данных.");
        }
        final List<ModelClient.Reason> reasons = facts.reasons();
        if (reasons.isEmpty()) {
            text.append(" Показатели покупателя, повлиявшие на оценку, не переданы: оценка риска получена вместе с данными.");
        }
        final String lowers = reasons.stream().filter(reason -> !"INCREASES_RISK".equals(reason.direction()))
                .map(reason -> lower(reason.title())).collect(Collectors.joining(", "));
        final String raises = reasons.stream().filter(reason -> "INCREASES_RISK".equals(reason.direction()))
                .map(reason -> lower(reason.title())).collect(Collectors.joining(", "));
        if (!lowers.isEmpty()) {
            text.append(" Снижают риск: ").append(lowers).append('.');
        }
        if (!raises.isEmpty()) {
            text.append(" Повышают риск: ").append(raises).append('.');
        }
        if (facts.delayProbable()) {
            text.append(" Вероятна задержка оплаты, поэтому аванс на ступень ниже.");
        }
        return text.toString();
    }

    static String templateDraft(final ExplanationFacts facts) {
        return "Добрый день! По отгрузке покупателю " + facts.buyerTitle() + " (" + UpdNumber.labelWithNo(facts.updNumber())
                + " от " + facts.updDate() + ") банк готов сразу выплатить " + facts.ead()
                + " руб., не дожидаясь оплаты от покупателя. Срок - " + facts.termDays() + " дн., стоимость - " + facts.price()
                + " руб. (" + facts.priceRate() + " годовых). Если интересно, оформим факторинг в ВТБ Факторинге: от вас нужно только согласие.";
    }

    /**
     * «Около 2 из 100 таких компаний за год не смогут расплатиться по долгам» (одна компания - «не сможет»).
     */
    static String riskOutOf100Sentence(final String outOf100) {
        final boolean one = outOf100.startsWith("меньше") || "около 1 из 100".equals(outOf100);
        return Character.toUpperCase(outOf100.charAt(0)) + outOf100.substring(1) + " таких компаний за год "
                + (one ? "не сможет" : "не смогут") + " расплатиться по долгам.";
    }

    private static String lower(final String title) {
        return title == null || title.isEmpty() ? "" : Character.toLowerCase(title.charAt(0)) + title.substring(1);
    }

    private String buyerName(final Long buyerId) {
        if (buyerId == null) {
            return "";
        }
        return buyerNames.computeIfAbsent(buyerId, id -> jdbc.queryForObject("select name from buyer where id = :id",
                new MapSqlParameterSource("id", id), String.class));
    }

    private Texts cached(final String hash) {
        return jdbc.query("select reasons_text, message_draft from llm_text_cache where input_hash = :hash",
                        new MapSqlParameterSource("hash", hash),
                        (rs, rowNum) -> Texts.of(rs.getString("reasons_text"), rs.getString("message_draft"), Source.CACHE))
                .stream().findFirst().orElse(null);
    }

    private void cache(final String hash, final Texts texts) {
        jdbc.update("""
                        insert into llm_text_cache (input_hash, provider, reasons_text, message_draft)
                        values (:hash, :provider, :reasons, :draft)
                        on conflict (input_hash) do nothing""",
                new MapSqlParameterSource()
                        .addValue("hash", hash)
                        .addValue("provider", provider())
                        .addValue("reasons", texts.reasonsText())
                        .addValue("draft", texts.messageDraft()));
    }

    private static String hash(final String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String resource(final String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Нет промпта " + path, e);
        }
    }
}
