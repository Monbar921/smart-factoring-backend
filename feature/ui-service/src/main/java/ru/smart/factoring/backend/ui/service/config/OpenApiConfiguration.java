package ru.smart.factoring.backend.ui.service.config;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import ru.smart.factoring.backend.security.JwtPayload;

@Configuration
public class OpenApiConfiguration {
    static {
        // JwtPayload берётся из заголовка Authorization, а не из запроса: в спецификации ему не место
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(JwtPayload.class);
    }
}
