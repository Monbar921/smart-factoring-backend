package ru.smart.factoring.backend.annotation;

import org.springframework.context.annotation.Import;
import ru.smart.factoring.backend.autoconfiguration.SmartFactoringBackendClientAutoConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import(SmartFactoringBackendClientAutoConfiguration.class)
public @interface SmartFactoringBackendClient {
    String value() default "smart-factoring-backend-client";
}
