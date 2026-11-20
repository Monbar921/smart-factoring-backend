package ru.smart.factoring.backend.factoring.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import ru.smart.factoring.backend.factoring.llm.DisabledLlmClient;
import ru.smart.factoring.backend.factoring.llm.GigaChatClient;
import ru.smart.factoring.backend.factoring.llm.LlmClient;
import ru.smart.factoring.backend.factoring.llm.LlmProperties;
import ru.smart.factoring.backend.factoring.llm.MaskingLlmClient;
import ru.smart.factoring.backend.factoring.llm.OnPremLlmClient;
import ru.smart.factoring.backend.factoring.ml.HttpModelClient;
import ru.smart.factoring.backend.factoring.ml.ModelClient;
import ru.smart.factoring.backend.factoring.ml.ModelProperties;
import ru.smart.factoring.backend.factoring.ml.StubModelClient;
import ru.smart.factoring.backend.factoring.source.ManualUploadProperties;

import java.util.Locale;

@Configuration
@EnableConfigurationProperties({ManualUploadProperties.class, ModelProperties.class, LlmProperties.class})
public class FactoringConfiguration {
    private static final int DEFAULT_LLM_THREADS = 8;

    /**
     * Поток прогона: одновременно выполняется не более одного прогона (ПР-3), второй не пускает ещё и БД.
     */
    @Bean
    public ThreadPoolTaskExecutor runTaskExecutor() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("run-");
        executor.initialize();
        return executor;
    }

    /**
     * Параллельные запросы к LLM за текстами карточек (НФР-1.1): smart-factoring.llm.parallelism, по умолчанию 8.
     */
    @Bean
    public ThreadPoolTaskExecutor llmTaskExecutor(final LlmProperties properties) {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        final int threads = llmParallelism(properties);
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setThreadNamePrefix("llm-");
        executor.initialize();
        return executor;
    }

    /**
     * LLM: gigachat - GigaChat API, onprem - LLM в контуре банка с API вида OpenAI (Р-74), stub и off - без обращений
     * к LLM (Р-12, Р-48). Один клиент на прогон и на запросы сервиса моделей: общий токен и общий предел одновременных
     * запросов. С маскированием (LLM_MASK, по умолчанию да) ИНН и ОГРН в любом запросе уходят метками (Р-73).
     */
    @Bean
    public LlmClient llmClient(final LlmProperties properties) {
        final String mode = properties.mode() == null ? "stub" : properties.mode().toLowerCase(Locale.ROOT);
        final LlmClient client = switch (mode) {
            case "gigachat" -> new GigaChatClient(properties.gigachat(), llmParallelism(properties));
            case "onprem" -> new OnPremLlmClient(properties.onprem(), llmParallelism(properties));
            default -> null;
        };
        if (client == null) {
            return new DisabledLlmClient(mode);
        }
        return properties.masked() ? new MaskingLlmClient(client) : client;
    }

    private static int llmParallelism(final LlmProperties properties) {
        return properties.parallelism() != null && properties.parallelism() > 0 ? properties.parallelism() : DEFAULT_LLM_THREADS;
    }

    /**
     * Сервис моделей: http - smart-factoring-ml, stub - встроенная заглушка (Р-35).
     */
    @Bean
    public ModelClient modelClient(final ModelProperties properties) {
        if ("http".equalsIgnoreCase(properties.mode())) {
            return new HttpModelClient(properties);
        }
        return new StubModelClient(properties.stubScoresDir());
    }
}
