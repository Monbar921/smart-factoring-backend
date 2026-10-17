package ru.smart.factoring.backend.autoconfiguration;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;

import java.lang.annotation.Annotation;
import java.util.Map;

@Getter
@RequiredArgsConstructor
public abstract class AbstractClientAutoConfiguration<T extends Annotation> {
    private static final String CLIENT_NAME_URL_PLACEHOLDER = "%s.%s.url";
    private static final String CONFIG_ROOT =
            "%s.config".formatted(FeignClientProperties.class.getAnnotation(ConfigurationProperties.class).value());
    private final Environment environment;
    private final ApplicationContext applicationContext;
    private final Class<T> annotationClass;

    @SneakyThrows
    protected String getClientName() {
        return (String) AnnotationUtils.getValue(getEnableAnnotation());
    }

    protected String getClientUrl(final String clientName) {
        return environment.getRequiredProperty(String.format(CLIENT_NAME_URL_PLACEHOLDER, CONFIG_ROOT, clientName));
    }

    private T getEnableAnnotation() {
        final Map<String, Object> beans = applicationContext.getBeansWithAnnotation(annotationClass);
        final Object annotatedBean = beans
                .values()
                .stream()
                .findFirst()
                .orElseThrow();
        return annotatedBean
                .getClass()
                .getAnnotation(annotationClass);
    }
}
