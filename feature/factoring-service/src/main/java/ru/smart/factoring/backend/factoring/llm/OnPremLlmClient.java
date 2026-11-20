package ru.smart.factoring.backend.factoring.llm;

import java.time.Duration;

/**
 * LLM в контуре банка (LLM_MODE=onprem, Р-74): любой сервер с API вида OpenAI - vLLM, Ollama, llama.cpp server,
 * LM Studio - с российской открытой моделью (GigaChat Lite, T-Pro, YandexGPT Lite и др.). Данные не покидают контур.
 * Ключ необязателен: если задан - заголовок Authorization: Bearer.
 */
public class OnPremLlmClient extends AbstractChatCompletionsClient {
    private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(1);

    private final String authorization;

    public OnPremLlmClient(final LlmProperties.OnPrem properties, final int parallelism) {
        this(properties, parallelism, DEFAULT_BACKOFF);
    }

    OnPremLlmClient(final LlmProperties.OnPrem properties, final int parallelism, final Duration backoff) {
        super("on-prem LLM", "ONPREM_LLM_MODEL", required(properties).apiUrl(), properties.model(), properties.timeout(),
                properties.caCert(), parallelism, backoff);
        this.authorization = properties.apiKey() == null || properties.apiKey().isBlank() ? null : "Bearer " + properties.apiKey();
    }

    private static LlmProperties.OnPrem required(final LlmProperties.OnPrem properties) {
        if (properties == null || properties.apiUrl() == null || properties.apiUrl().isBlank()) {
            throw new IllegalStateException("LLM_MODE=onprem, но не задан ONPREM_LLM_URL");
        }
        if (properties.model() == null || properties.model().isBlank()) {
            throw new IllegalStateException("LLM_MODE=onprem, но не задана ONPREM_LLM_MODEL - модель на сервере (GET /models)");
        }
        return properties;
    }

    @Override
    protected String authorization() {
        return authorization;
    }
}
