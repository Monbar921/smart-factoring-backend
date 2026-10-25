package ru.smart.factoring.backend.factoring.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import ru.smart.factoring.backend.factoring.ml.HttpModelClient;
import ru.smart.factoring.backend.factoring.ml.ModelClient;
import ru.smart.factoring.backend.factoring.ml.ModelProperties;
import ru.smart.factoring.backend.factoring.ml.StubModelClient;
import ru.smart.factoring.backend.factoring.source.DemoProperties;

@Configuration
@EnableConfigurationProperties({DemoProperties.class, ModelProperties.class})
public class FactoringConfiguration {

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
