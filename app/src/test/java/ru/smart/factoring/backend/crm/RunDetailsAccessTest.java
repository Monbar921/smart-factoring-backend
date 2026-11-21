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
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Объектный доступ на экране процесса (НФР-9, п. 1.1а-21): менеджер видит в детализации запуска только своих клиентов,
 * их УПД, покупателей и записи журнала; путь чужого УПД - 403. Оператор и руководитель видят всех.
 * <p>
 * В test application.yml: anna - менеджер M1, Boris - M2. После запуска один клиент фикстуры переходит к M2 (Boris),
 * остальные остаются у M1 (anna).
 */
class RunDetailsAccessTest extends AbstractSmartFactoringBackendTest {
    private static final String ANNA = token("anna", "\"manager\"");
    private static final String BORIS = token("boris", "\"manager\"");
    private static final String NOT_MAPPED = token("ivan", "\"manager\"");
    private static final String OPERATOR = token("oleg", "\"operator\"");
    private static final String SUPERVISOR = token("olga", "\"supervisor\"");
    private static final String BORIS_CLIENT_INN = "7710000001";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long runId;
    private long borisClientId;
    private long borisUpdId;
    private long annaUpdId;

    @BeforeEach
    void setUp() throws InterruptedException {
        Fixture.importFixture(jdbcTemplate);
        runId = Fixture.run(Map.of()).get("id").asLong();
        jdbcTemplate.update("insert into manager (code, full_name) values ('M2', 'Борис Тестов')");
        jdbcTemplate.update("update client set manager_id = (select id from manager where code = 'M2') where inn = ?", BORIS_CLIENT_INN);
        borisClientId = jdbcTemplate.queryForObject("select id from client where inn = ?", Long.class, BORIS_CLIENT_INN);
        borisUpdId = jdbcTemplate.queryForObject("""
                select min(u.id) from upd_state us join upd u on u.id = us.upd_id where us.run_id = ? and u.client_id = ?""",
                Long.class, runId, borisClientId);
        annaUpdId = jdbcTemplate.queryForObject("""
                select min(u.id) from upd_state us join upd u on u.id = us.upd_id where us.run_id = ? and u.client_id <> ?""",
                Long.class, runId, borisClientId);
    }

    @AfterEach
    void tearDown() {
        TestDatabase.clean(jdbcTemplate);
    }

    @Test
    void clientsOfRunAreFilteredByManager() {
        final Set<Long> all = ids(Api.getAs(runPath("/clients"), OPERATOR).body(), "clientId");
        assertThat(all).contains(borisClientId).hasSizeGreaterThan(1);
        assertThat(ids(Api.getAs(runPath("/clients"), SUPERVISOR).body(), "clientId")).isEqualTo(all);
        assertThat(ids(Api.getAs(runPath("/clients"), BORIS).body(), "clientId")).containsExactly(borisClientId);
        final Set<Long> anna = ids(Api.getAs(runPath("/clients"), ANNA).body(), "clientId");
        assertThat(anna).doesNotContain(borisClientId).hasSize(all.size() - 1);
        assertThat(Api.getAs(runPath("/clients"), NOT_MAPPED).body()).isEmpty();
    }

    @Test
    void updsOfRunAreFilteredByManager() {
        final JsonNode operator = Api.getAs(runPath("/upds?size=500"), OPERATOR).body();
        final JsonNode boris = Api.getAs(runPath("/upds?size=500"), BORIS).body();
        final JsonNode anna = Api.getAs(runPath("/upds?size=500"), ANNA).body();
        assertThat(boris.get("items")).isNotEmpty()
                .allSatisfy(upd -> assertThat(upd.get("client").get("id").asLong()).isEqualTo(borisClientId));
        assertThat(anna.get("items")).isNotEmpty()
                .allSatisfy(upd -> assertThat(upd.get("client").get("id").asLong()).isNotEqualTo(borisClientId));
        assertThat(boris.get("total").asLong() + anna.get("total").asLong()).isEqualTo(operator.get("total").asLong());
        // фильтр по чужому клиенту не обходит ограничение
        assertThat(Api.getAs(runPath("/upds?clientId=" + borisClientId), ANNA).body().get("total").asLong()).isZero();
        assertThat(Api.getAs(runPath("/upds"), NOT_MAPPED).body().get("total").asLong()).isZero();
    }

    @Test
    void funnelCountsOnlyOwnUpds() {
        final long operator = Api.getAs(runPath("/funnel"), OPERATOR).body().get(0).get("count").asLong();
        final long boris = Api.getAs(runPath("/funnel"), BORIS).body().get(0).get("count").asLong();
        final long anna = Api.getAs(runPath("/funnel"), ANNA).body().get(0).get("count").asLong();
        assertThat(boris).isPositive();
        assertThat(boris + anna).isEqualTo(operator);
        assertThat(Api.getAs(runPath("/funnel"), NOT_MAPPED).body().get(0).get("count").asLong()).isZero();
    }

    @Test
    void traceOfForeignUpdIsForbidden() {
        assertThat(Api.getAs(runPath("/upds/" + borisUpdId + "/trace"), BORIS).status()).isEqualTo(200);
        assertThat(Api.getAs(runPath("/upds/" + annaUpdId + "/trace"), ANNA).status()).isEqualTo(200);
        assertThat(Api.getAs(runPath("/upds/" + borisUpdId + "/trace"), OPERATOR).status()).isEqualTo(200);
        assertThat(Api.getAs(runPath("/upds/" + borisUpdId + "/trace"), SUPERVISOR).status()).isEqualTo(200);

        final Api.Response foreign = Api.getAs(runPath("/upds/" + borisUpdId + "/trace"), ANNA);
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.body().get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(Api.getAs(runPath("/upds/" + annaUpdId + "/trace"), BORIS).status()).isEqualTo(403);
        assertThat(Api.getAs(runPath("/upds/" + annaUpdId + "/trace"), NOT_MAPPED).status()).isEqualTo(403);
    }

    @Test
    void buyersShowOnlyOwnUpdsAndAmounts() {
        final Set<Long> borisBuyers = new HashSet<>(jdbcTemplate.queryForList("""
                select distinct u.buyer_id from upd_state us join upd u on u.id = us.upd_id
                join buyer_score bs on bs.run_id = us.run_id and bs.buyer_id = u.buyer_id
                where us.run_id = ? and u.client_id = ?""", Long.class, runId, borisClientId));
        final JsonNode boris = Api.getAs(runPath("/buyers"), BORIS).body();
        assertThat(ids(boris, "buyerId")).isEqualTo(borisBuyers);
        final long borisOpen = jdbcTemplate.queryForObject("""
                select count(*) from upd_state us join upd u on u.id = us.upd_id
                join buyer_score bs on bs.run_id = us.run_id and bs.buyer_id = u.buyer_id
                where us.run_id = ? and u.client_id = ? and us.payment_status <> 'PAID'""", Long.class, runId, borisClientId);
        long shown = 0;
        for (final JsonNode buyer : boris) {
            shown += buyer.get("openUpds").asLong();
        }
        assertThat(shown).isEqualTo(borisOpen);
        assertThat(Api.getAs(runPath("/buyers"), NOT_MAPPED).body()).isEmpty();
        assertThat(Api.getAs(runPath("/buyers"), OPERATOR).body().size()).isGreaterThanOrEqualTo(boris.size());
    }

    @Test
    void logHidesForeignClientEntries() {
        final Integer foreignEntries = jdbcTemplate.queryForObject("""
                select count(*) from run_log l where l.run_id = ? and (l.client_id = ?
                    or l.upd_id in (select id from upd where client_id = ?) or l.offer_id in (select id from offer where client_id = ?))""",
                Integer.class, runId, borisClientId, borisClientId, borisClientId);
        final JsonNode operator = Api.getAs(runPath("/log?size=1000"), OPERATOR).body();
        final JsonNode anna = Api.getAs(runPath("/log?size=1000"), ANNA).body();
        assertThat(anna.get("total").asLong()).isEqualTo(operator.get("total").asLong() - foreignEntries);
        assertThat(anna.get("items")).allSatisfy(entry -> assertThat(entry.get("clientId").isNull()
                || entry.get("clientId").asLong() != borisClientId).isTrue());
        // записи о запуске в целом видны всем
        assertThat(Api.getAs(runPath("/log"), NOT_MAPPED).body().get("items"))
                .anySatisfy(entry -> assertThat(entry.get("eventCode").asString()).isEqualTo("RUN_STARTED"));
    }

    @Test
    void runListAndSummaryStayVisible() {
        assertThat(Api.getAs("/runs", ANNA).status()).isEqualTo(200);
        assertThat(Api.getAs(runPath(""), BORIS).status()).isEqualTo(200);
        assertThat(Api.getAs(runPath("/economics"), BORIS).status()).isEqualTo(200);
    }

    private String runPath(final String suffix) {
        return "/runs/" + runId + suffix;
    }

    private static Set<Long> ids(final JsonNode array, final String field) {
        final Set<Long> ids = new HashSet<>();
        array.forEach(node -> ids.add(node.get(field).asLong()));
        return ids;
    }

    private static String token(final String login, final String roles) {
        return "{\"preferred_username\":\"" + login + "\",\"azp\":\"smart-factoring-ui\",\"realm_access\":{\"roles\":[" + roles + "]}}";
    }
}
