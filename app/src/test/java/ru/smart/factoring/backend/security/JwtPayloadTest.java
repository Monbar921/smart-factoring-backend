package ru.smart.factoring.backend.security;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.smart.factoring.backend.ui.service.controller.MaintenanceController;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Без контекста Spring и БД: reader и resolver на настоящем контроллере через standalone MockMvc.
 */
class JwtPayloadTest {
    private static final String PAYLOAD = """
            {"iss":"http://keycloak.test/realms/smart-factoring-test","sub":"c0ffee00-1111-2222-3333-444455556666",
             "preferred_username":"tester","email":"tester@smart-factoring.test",
             "realm_access":{"roles":["user","default-roles-smart-factoring-test"]},"exp":1900000000}
            """;

    private final JwtPayloadReader reader = new JwtPayloadReader(JsonMapper.builder().build());

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MaintenanceController(), new OptionalUserController())
            .setCustomArgumentResolvers(new JwtPayloadArgumentResolver(reader))
            .build();

    @Test
    void payloadIsReadWithoutSignatureCheck() {
        final JwtPayload payload = reader.read("Bearer " + jwt(PAYLOAD)).orElseThrow();

        assertThat(payload.subject()).isEqualTo("c0ffee00-1111-2222-3333-444455556666");
        assertThat(payload.username()).isEqualTo("tester");
        assertThat(payload.email()).isEqualTo("tester@smart-factoring.test");
        assertThat(payload.roles()).containsExactly("user", "default-roles-smart-factoring-test");
        assertThat(payload.hasRole("user")).isTrue();
        assertThat(payload.hasRole("admin")).isFalse();
    }

    @Test
    void bearerPrefixIsCaseInsensitive() {
        assertThat(reader.read("bearer " + jwt(PAYLOAD))).isPresent();
    }

    @Test
    void notJwtIsIgnored() {
        assertThat(reader.read(null)).isEmpty();
        assertThat(reader.read("Basic dXNlcjpwYXNz")).isEmpty();
        assertThat(reader.read("Bearer not-a-jwt")).isEmpty();
        assertThat(reader.read("Bearer a.%%%.c")).isEmpty();
        assertThat(reader.read("Bearer " + jwt("not json"))).isEmpty();
    }

    @Test
    void tokenWithoutRolesHasNoRoles() {
        assertThat(reader.read("Bearer " + jwt("{\"sub\":\"1\"}")).orElseThrow().roles()).isEmpty();
    }

    @Test
    void nullClaimIsKept() {
        final JwtPayload payload = reader.read("Bearer " + jwt("{\"sub\":\"1\",\"email\":null}")).orElseThrow();

        assertThat(payload.email()).isNull();
        assertThat(payload.claims()).containsKey("email");
    }

    @Test
    void controllerReceivesPayload() throws Exception {
        mockMvc.perform(get("/internal/rest/maintenance/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(PAYLOAD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferred_username").value("tester"))
                .andExpect(jsonPath("$.realm_access.roles[0]").value("user"));
    }

    @Test
    void controllerWithoutTokenRespondsUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/rest/maintenance/whoami"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * JWT с произвольной подписью: backend её не проверяет.
     */
    private static String jwt(final String payload) {
        final Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + base64.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".c2lnbmF0dXJl";
    }

    @Test
    void nullablePayloadIsNullWithoutToken() throws Exception {
        mockMvc.perform(get("/test/optional-user"))
                .andExpect(status().isOk())
                .andExpect(content().string("anonymous"));
    }

    @Test
    void nullablePayloadIsFilledWithToken() throws Exception {
        mockMvc.perform(get("/test/optional-user").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(PAYLOAD)))
                .andExpect(status().isOk())
                .andExpect(content().string("tester"));
    }

    @Test
    void methodWithoutPayloadDoesNotNeedToken() throws Exception {
        mockMvc.perform(get("/internal/rest/maintenance/hello"))
                .andExpect(status().isOk());
    }

    /**
     * Метод, который зовут и пользователи через gateway, и другие сервисы внутри сети без токена.
     */
    @RestController
    static class OptionalUserController {
        @GetMapping("/test/optional-user")
        String user(final @Nullable JwtPayload user) {
            return user != null ? user.username() : "anonymous";
        }
    }
}
