package ru.smart.factoring.backend.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    /** claim с кодами менеджеров пользователя, см. {@link #managerCodes()} */
    public static final String MANAGER_CODES = "manager_codes";

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

    /**
     * Клиент Keycloak, которому выдан токен (azp): по нему узнаётся гостевой вход «Запуск демо».
     */
    public String authorizedParty() {
        return string("azp");
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

    /**
     * Коды менеджеров (managers.csv), за которых работает пользователь: claim {@value #MANAGER_CODES}, его кладёт в токен
     * mapper Keycloak из атрибута пользователя manager_code. Один код без multivalued приходит строкой - тоже принимаем.
     */
    public Set<String> managerCodes() {
        final Object value = claims.get(MANAGER_CODES);
        final Stream<?> codes = value instanceof Collection<?> collection ? collection.stream() : Stream.ofNullable(value);
        return codes.filter(Objects::nonNull).map(String::valueOf).map(String::trim).filter(code -> !code.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String string(final String claim) {
        final Object value = claims.get(claim);
        return value != null ? value.toString() : null;
    }
}
