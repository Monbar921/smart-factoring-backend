package ru.smart.factoring.backend.factoring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
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
}
