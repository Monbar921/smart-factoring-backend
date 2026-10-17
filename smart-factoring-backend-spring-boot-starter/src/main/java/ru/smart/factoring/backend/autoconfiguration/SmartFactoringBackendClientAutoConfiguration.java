package ru.smart.factoring.backend.autoconfiguration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.openfeign.FeignClientBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import ru.smart.factoring.backend.client.SmartFactoringBackendClient;

@Configuration
@ConditionalOnBean(annotation = ru.smart.factoring.backend.annotation.SmartFactoringBackendClient.class)
public class SmartFactoringBackendClientAutoConfiguration extends AbstractClientAutoConfiguration<ru.smart.factoring.backend.annotation.SmartFactoringBackendClient> {
    public SmartFactoringBackendClientAutoConfiguration(final Environment environment,
                                                        final ApplicationContext applicationContext) {
        super(environment, applicationContext, ru.smart.factoring.backend.annotation.SmartFactoringBackendClient.class);
    }

    @Bean
    @ConditionalOnMissingBean
    public SmartFactoringBackendClient investApiBondClient() {
        final String clientName = getClientName();
        return new FeignClientBuilder(getApplicationContext())
                .forType(
                        SmartFactoringBackendClient.class,
                        clientName
                )
                .url(getClientUrl(clientName))
                .path("/internal/rest")
                .build();
    }
}
