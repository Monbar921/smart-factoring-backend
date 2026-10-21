package ru.smart.factoring.backend.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Payload access token Keycloak, с которым пришёл запрос.
 * <p>
 * Подпись токена здесь НЕ проверяется: это делает gateway, а backend доступен только из сети стенда, то есть только
 * через него. Поэтому полагаться на эти данные можно только пока backend не опубликован наружу в обход gateway.
 * <p>
 * Получить в контроллере - параметром метода:
 * <ul>
 *     <li>{@code public X handle(final JwtPayload user)} - нужен пользователь, без токена 401;</li>
 *     <li>{@code public X handle(final @Nullable JwtPayload user)} - токена может не быть (вызов из другого сервиса
 *     внутри сети), тогда null.</li>
 * </ul>
 * Методы без такого параметра токен не требуют.
 *
 * @param claims все claims токена как есть
 */
public record JwtPayload(Map<String, Object> claims) {

    /**
     * Id пользователя в Keycloak.
     */
    public String subject() {
        return string("sub");
    }

    /**
     * Логин пользователя.
     */
    public String username() {
        return string("preferred_username");
    }

    public String email() {
        return string("email");
    }

    /**
     * Роли realm Keycloak (realm_access.roles).
     */
    public List<String> roles() {
        if (claims.get("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            return roles.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    public boolean hasRole(final String role) {
        return roles().contains(role);
    }

    private String string(final String claim) {
        final Object value = claims.get(claim);
        return value != null ? value.toString() : null;
    }
}
