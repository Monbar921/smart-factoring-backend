package ru.smart.factoring.backend.crm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.smart.factoring.backend.AbstractSmartFactoringBackendTest;
import ru.smart.factoring.backend.support.Api;
import ru.smart.factoring.backend.support.Fixture;
import ru.smart.factoring.backend.support.TestDatabase;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Карточку и предложения видит только менеджер клиента (НФР-9, Р-75). Коды менеджеров - в claim manager_codes токена
 * (Р-90): anna - M1 (все клиенты фикстуры, массивом), boris - M2 (клиентов нет, строкой - один код без multivalued).
 */
class ManagerAccessTest extends AbstractSmartFactoringBackendTest {
    private static final String ANNA = token("anna", "\"manager\"", "[\"M1\"]");
    private static final String BORIS = token("boris", "\"manager\"", "\"M2\"");
    private static final String NOT_MAPPED = token("ivan", "\"manager\"", null);
    private static final String SUPERVISOR = token("olga", "\"supervisor\"", null);
    private static final String GUEST = """
            {"preferred_username":"demo","azp":"smart-factoring-guest","realm_access":{"roles":["operator","manager"]}}""";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long managerId;
    private long clientId;
    private long offerId;

    @BeforeEach
    void setUp() throws InterruptedException {
        Fixture.importFixture(jdbcTemplate);
        Fixture.run(Map.of());
        managerId = jdbcTemplate.queryForObject("select id from manager where code = 'M1'", Long.class);
        clientId = jdbcTemplate.queryForObject("select id from client where inn = '7710000001'", Long.class);
        offerId = jdbcTemplate.queryForObject("select min(id) from offer where status = 'QUEUED'", Long.class);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void managerSeesOwnClients() {
        assertThat(Api.getAs("/managers", ANNA).body()).singleElement()
                .satisfies(manager -> assertThat(manager.get("code").asString()).isEqualTo("M1"));
        assertThat(Api.getAs("/managers/" + managerId + "/queue", ANNA).body()).hasSize(12);
        assertThat(Api.getAs("/managers/" + managerId + "/notifications", ANNA).status()).isEqualTo(200);
        assertThat(Api.getAs("/clients/" + clientId + "/card", ANNA).status()).isEqualTo(200);
        assertThat(Api.postAs("/offers/" + offerId + "/transfer", null, ANNA).status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select decided_by from offer where id = ?", String.class, offerId)).isEqualTo("anna");
    }

    @Test
    void otherManagerGetsForbidden() {
        assertThat(Api.getAs("/managers", BORIS).body()).isEmpty();
        for (final String path : new String[] {"/managers/" + managerId + "/queue", "/managers/" + managerId + "/notifications",
                "/clients/" + clientId + "/card"}) {
            final Api.Response response = Api.getAs(path, BORIS);
            assertThat(response.status()).as(path).isEqualTo(403);
            assertThat(response.body().get("code").asString()).isEqualTo("FORBIDDEN");
        }
        assertThat(Api.postAs("/offers/" + offerId + "/transfer", null, BORIS).status()).isEqualTo(403);
        assertThat(Api.postAs("/offers/" + offerId + "/reject", Map.of("comment", "чужое"), BORIS).status()).isEqualTo(403);
        assertThat(jdbcTemplate.queryForObject("select status from offer where id = ?", String.class, offerId)).isEqualTo("QUEUED");
    }

    @Test
    void managerWithoutMappingSeesNothing() {
        assertThat(Api.getAs("/managers", NOT_MAPPED).body()).isEmpty();
        assertThat(Api.getAs("/clients/" + clientId + "/card", NOT_MAPPED).status()).isEqualTo(403);
    }

    @Test
    void supervisorAndGuestSeeEveryone() {
        assertThat(Api.getAs("/managers", SUPERVISOR).body()).hasSize(1);
        assertThat(Api.getAs("/clients/" + clientId + "/card", SUPERVISOR).status()).isEqualTo(200);
        assertThat(Api.getAs("/managers", GUEST).body()).hasSize(1);
        assertThat(Api.getAs("/managers/" + managerId + "/queue", GUEST).status()).isEqualTo(200);
    }

    /**
     * @param managerCodes значение claim manager_codes как JSON (массив или строка), null - claim нет
     */
    private static String token(final String login, final String roles, final String managerCodes) {
        return "{\"preferred_username\":\"" + login + "\",\"azp\":\"smart-factoring-ui\",\"realm_access\":{\"roles\":[" + roles + "]}"
                + (managerCodes != null ? ",\"manager_codes\":" + managerCodes : "") + "}";
    }
}
