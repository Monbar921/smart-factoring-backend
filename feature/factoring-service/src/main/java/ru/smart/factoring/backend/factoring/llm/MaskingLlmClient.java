package ru.smart.factoring.backend.factoring.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Последний рубеж маскирования перед LLM (Р-73): в любом запросе - и текстов карточек, и сервиса моделей через
 * POST /internal/rest/llm/chat/completions - отдельно стоящие числа длиной ИНН и ОГРН (10, 12, 13, 15 цифр)
 * заменяются метками ID_n, в ответе подставляются обратно. Названия компаний по тексту не найти - их маскирует тот,
 * кто знает, что в тексте: тексты карточек - {@link Pseudonyms}, сервис моделей - сам.
 */
public class MaskingLlmClient implements LlmClient {
    private static final Pattern IDENTIFIER = Pattern.compile("(?<![0-9])(?:[0-9]{15}|[0-9]{13}|[0-9]{12}|[0-9]{10})(?![0-9])");
    private static final Pattern TOKEN = Pattern.compile("ID_([0-9]+)(?![0-9])");

    private final LlmClient delegate;

    public MaskingLlmClient(final LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public List<String> models() {
        return delegate.models();
    }

    @Override
    public Completion complete(final CompletionRequest request) {
        // одно и то же число во всех сообщениях - одна метка: LLM видит, что речь об одной компании
        final Map<String, String> tokens = new LinkedHashMap<>();
        final List<Message> masked = request.messages().stream()
                .map(message -> new Message(message.role(), mask(message.content(), tokens)))
                .toList();
        final Completion completion = delegate.complete(new CompletionRequest(masked, request.temperature(), request.maxTokens(),
                request.model()));
        if (tokens.isEmpty()) {
            return completion;
        }
        return new Completion(restore(completion.content(), tokens), completion.model(), completion.finishReason(), completion.usage());
    }

    static String mask(final String text, final Map<String, String> tokens) {
        if (text == null) {
            return null;
        }
        return IDENTIFIER.matcher(text).replaceAll(match -> {
            final String token = tokens.computeIfAbsent(match.group(), value -> "ID_" + (tokens.size() + 1));
            return Matcher.quoteReplacement(token);
        });
    }

    static String restore(final String text, final Map<String, String> tokens) {
        if (text == null) {
            return null;
        }
        final Map<String, String> values = new LinkedHashMap<>();
        tokens.forEach((value, token) -> values.put(token, value));
        return TOKEN.matcher(text).replaceAll(match -> Matcher.quoteReplacement(values.getOrDefault(match.group(), match.group())));
    }
}
