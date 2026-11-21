package ru.smart.factoring.backend.factoring.offer;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.factoring.error.ApiException;
import ru.smart.factoring.backend.security.JwtPayload;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Карточку и предложения видит только менеджер клиента (НФР-9, Р-75). Кто чей менеджер - по логину из токена:
 * MANAGER_LOGINS = «логин:код менеджера» через запятую (код - managers.csv). На пилоте соответствие придёт из CRM банка.
 * <ul>
 *     <li>роль manager - только менеджеры, сопоставленные его логину; не сопоставлен - никто;</li>
 *     <li>роль supervisor (руководитель) - все менеджеры;</li>
 *     <li>гостевой вход «Запуск демо» - все, только просмотр (изменения гостю запрещает gateway);</li>
 *     <li>запрос без токена - изнутри сети стенда, мимо gateway (интеграции): все;</li>
 *     <li>MANAGER_SCOPE=false - проверка выключена, все видят всех (как в эмуляторе CRM до Р-75).</li>
 * </ul>
 */
@Slf4j
@Component
public class ManagerAccess {
    /** параметр SQL с кодами менеджеров для {@link #clientCondition} */
    public static final String CODES_PARAM = "managerCodes";
    static final String SUPERVISOR = "supervisor";
    static final String OPERATOR = "operator";

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean enabled;
    private final String guestClientId;
    /** логин → коды менеджеров */
    private final Map<String, Set<String>> managerCodes;

    public ManagerAccess(final NamedParameterJdbcTemplate jdbc,
                         @Value("${smart-factoring.access.manager-scope:true}") final boolean enabled,
                         @Value("${smart-factoring.access.manager-logins:}") final String managerLogins,
                         @Value("${smart-factoring.guest-client-id:smart-factoring-guest}") final String guestClientId) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.guestClientId = guestClientId;
        this.managerCodes = parse(managerLogins);
    }

    public Scope scope(final @Nullable JwtPayload user) {
        if (!enabled || user == null || guestClientId.equals(user.authorizedParty()) || user.roles().contains(SUPERVISOR)) {
            return Scope.ALL;
        }
        final String login = user.username() == null ? "" : user.username().toLowerCase(Locale.ROOT);
        return new Scope(false, managerCodes.getOrDefault(login, Set.of()));
    }

    /**
     * Экран процесса (п. 1.1а-21): оператор запускает анализ по всем клиентам и видит всех; менеджер без роли operator -
     * только своих клиентов в детализации, журнале и списке клиентов запуска, как в CRM.
     */
    public Scope runScope(final @Nullable JwtPayload user) {
        if (user != null && user.roles().contains(OPERATOR)) {
            return Scope.ALL;
        }
        return scope(user);
    }

    /**
     * Условие SQL «клиент из колонки доступен»: для всех - true, иначе подзапрос по кодам менеджеров
     * (параметр {@value #CODES_PARAM}, значения - {@link Scope#codes()}).
     */
    public static String clientCondition(final Scope scope, final String clientIdColumn) {
        if (scope.all()) {
            return "true";
        }
        if (scope.codes().isEmpty()) {
            return "false";
        }
        return clientIdColumn + " in (select sc.id from client sc join manager sm on sm.id = sc.manager_id where sm.code in (:"
                + CODES_PARAM + "))";
    }

    /**
     * Путь УПД: чужой клиент - 403, как карточка.
     */
    public void checkUpd(final Scope scope, final long updId, final @Nullable JwtPayload user) {
        check(scope, "select m.code from upd u join client c on c.id = u.client_id join manager m on m.id = c.manager_id "
                + "where u.id = :id", updId, "УПД", user);
    }

    public void checkManager(final Scope scope, final long managerId, final @Nullable JwtPayload user) {
        check(scope, "select code from manager where id = :id", managerId, "очереди менеджера", user);
    }

    public void checkClient(final Scope scope, final long clientId, final @Nullable JwtPayload user) {
        check(scope, "select m.code from client c join manager m on m.id = c.manager_id where c.id = :id", clientId,
                "карточке клиента", user);
    }

    public void checkOffer(final Scope scope, final long offerId, final @Nullable JwtPayload user) {
        check(scope, "select m.code from offer o join manager m on m.id = o.manager_id where o.id = :id", offerId,
                "предложению", user);
    }

    /**
     * Нет такого объекта - пропускаем: 404 ответит сам сервис.
     */
    private void check(final Scope scope, final String sql, final long id, final String what, final @Nullable JwtPayload user) {
        if (scope.all()) {
            return;
        }
        final String code = jdbc.query(sql, new MapSqlParameterSource("id", id), (rs, rowNum) -> rs.getString(1))
                .stream().findFirst().orElse(null);
        if (code != null && !scope.allows(code)) {
            log.warn("Отказ в доступе к {} {}: {} - не его менеджер (НФР-9)", what, id, user != null ? user.username() : "?");
            throw ApiException.forbidden("Доступ только у менеджера клиента");
        }
    }

    static Map<String, Set<String>> parse(final String managerLogins) {
        final Map<String, Set<String>> codes = new HashMap<>();
        if (managerLogins == null || managerLogins.isBlank()) {
            return codes;
        }
        Arrays.stream(managerLogins.split(","))
                .map(String::trim)
                .filter(pair -> !pair.isEmpty())
                .forEach(pair -> {
                    final int colon = pair.indexOf(':');
                    if (colon <= 0 || colon == pair.length() - 1) {
                        throw new IllegalStateException("MANAGER_LOGINS: ожидается «логин:код менеджера», а не «" + pair + "»");
                    }
                    codes.computeIfAbsent(pair.substring(0, colon).trim().toLowerCase(Locale.ROOT), login -> new HashSet<>())
                            .add(pair.substring(colon + 1).trim());
                });
        return codes.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    /**
     * Чьи очереди, карточки и предложения доступны пользователю.
     *
     * @param all   все менеджеры
     * @param codes коды менеджеров, если не все
     */
    public record Scope(boolean all, Set<String> codes) {
        static final Scope ALL = new Scope(true, Set.of());

        public boolean allows(final String managerCode) {
            return all || codes.contains(managerCode);
        }
    }
}
