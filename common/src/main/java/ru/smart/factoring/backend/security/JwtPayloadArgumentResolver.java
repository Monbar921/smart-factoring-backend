package ru.smart.factoring.backend.security;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * Подставляет {@link JwtPayload} в параметры методов контроллеров.
 * Без токена - 401: через gateway такой запрос не пройдёт, значит, он пришёл в обход него.
 */
public class JwtPayloadArgumentResolver implements HandlerMethodArgumentResolver {
    private final JwtPayloadReader reader;

    public JwtPayloadArgumentResolver(final JwtPayloadReader reader) {
        this.reader = reader;
    }

    @Override
    public boolean supportsParameter(final MethodParameter parameter) {
        return JwtPayload.class.equals(parameter.getParameterType());
    }

    @Override
    public JwtPayload resolveArgument(final MethodParameter parameter,
                                      final ModelAndViewContainer mavContainer,
                                      final NativeWebRequest webRequest,
                                      final WebDataBinderFactory binderFactory) {
        return reader.read(webRequest.getHeader(HttpHeaders.AUTHORIZATION))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Нет JWT в заголовке Authorization"));
    }
}
