package ru.smart.factoring.backend.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class JwtPayloadWebConfiguration implements WebMvcConfigurer {
    private final JwtPayloadReader reader;

    public JwtPayloadWebConfiguration(final JwtPayloadReader reader) {
        this.reader = reader;
    }

    @Override
    public void addArgumentResolvers(final List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new JwtPayloadArgumentResolver(reader));
    }
}
