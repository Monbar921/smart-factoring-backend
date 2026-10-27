package ru.smart.factoring.backend.factoring.llm;

import org.springframework.http.HttpStatus;
import ru.smart.factoring.backend.factoring.error.ApiException;

import java.util.List;
import java.util.Map;

/**
 * LLM_MODE = stub или off: обращений к LLM нет, запросы сервиса моделей получают LLM_DISABLED.
 */
public class DisabledLlmClient implements LlmClient {
    private final String mode;

    public DisabledLlmClient(final String mode) {
        this.mode = mode;
    }

    @Override
    public String model() {
        return mode;
    }

    @Override
    public Completion complete(final CompletionRequest request) {
        throw disabled();
    }

    @Override
    public List<String> models() {
        throw disabled();
    }

    private ApiException disabled() {
        return new ApiException("LLM_DISABLED", HttpStatus.SERVICE_UNAVAILABLE, "LLM выключена: LLM_MODE=" + mode, Map.of("mode", mode));
    }
}
