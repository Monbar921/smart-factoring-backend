package ru.smart.factoring.backend.security;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Подставляет {@link JwtPayload} в параметры методов контроллеров:
 * <ul>
 *     <li>{@code JwtPayload user} - нужен пользователь: без токена 401;</li>
 *     <li>{@code @Nullable JwtPayload user} (org.jspecify.annotations.Nullable) - токена может не быть,
 *     например при вызове из другого сервиса внутри сети: тогда null.</li>
 * </ul>
 * Методы без параметра JwtPayload токен не проверяют вообще.
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
        final Optional<JwtPayload> payload = reader.read(webRequest.getHeader(HttpHeaders.AUTHORIZATION));
        // isOptional - параметр помечен аннотацией @Nullable
        if (parameter.isOptional()) {
            return payload.orElse(null);
        }
        return payload.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Нет JWT в заголовке Authorization"));
    }
}
