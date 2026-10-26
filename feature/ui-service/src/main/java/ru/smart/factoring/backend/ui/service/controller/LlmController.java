package ru.smart.factoring.backend.ui.service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.smart.factoring.backend.api.llm.LlmChatRequest;
import ru.smart.factoring.backend.api.llm.LlmChatResponse;
import ru.smart.factoring.backend.factoring.llm.LlmClient;

/**
 * LLM для сервиса моделей (Р-48): smart-factoring-ml к GigaChat напрямую не ходит, только сюда.
 * Ключ, токен и предел одновременных запросов - общие с прогоном.
 * <p>
 * Путь вне /internal/rest: gateway его не проксирует, доступен только из сети стенда.
 * Ошибки: 400 VALIDATION_ERROR, 503 LLM_DISABLED (LLM_MODE не gigachat) / LLM_UNAVAILABLE, 502 LLM_ERROR.
 */
@RestController
@RequestMapping("/internal/llm/v1")
@RequiredArgsConstructor
public class LlmController {
    private final LlmClient llm;

    @PostMapping("/chat/completions")
    public LlmChatResponse complete(@Valid @RequestBody final LlmChatRequest request) {
        final LlmClient.Completion completion = llm.complete(new LlmClient.CompletionRequest(
                request.messages().stream().map(message -> new LlmClient.Message(message.role(), message.content())).toList(),
                request.temperature(), request.maxTokens()));
        return new LlmChatResponse(completion.content(), completion.model(), completion.finishReason(),
                completion.usage().promptTokens(), completion.usage().completionTokens());
    }
}
