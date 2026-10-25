package ru.smart.factoring.backend;

import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

@SpringBootTest(classes = SmartFactoringBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(initializers = AbstractSmartFactoringBackendTest.Initializer.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractSmartFactoringBackendTest {
    @Autowired
    private JsonMapper jsonMapper;

    public static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:17.6")
            .withDatabaseName("smart_factoring")
            .withUsername("sa")
            .withPassword("sa")
            .withInitScript("sql/init-schema.sql")
            .withNetwork(Network.SHARED)
            .withReuse(true);

    static {
        postgreSQLContainer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(postgreSQLContainer::stop));
    }

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        public void initialize(final ConfigurableApplicationContext configurableApplicationContext) {
            TestPropertyValues
                    .of(
                            "spring.datasource.hikari.jdbc-url=" + postgreSQLContainer.getJdbcUrl(),
                            "spring.datasource.hikari.username=" + postgreSQLContainer.getUsername(),
                            "spring.datasource.hikari.password=" + postgreSQLContainer.getPassword()
                    )
                    .applyTo(configurableApplicationContext.getEnvironment());
        }
    }

    @SneakyThrows
    protected <T> T readObjectFromFile(final String json, final Class<T> clazz) {
        return jsonMapper.readValue(IOUtils.resourceToString(json, StandardCharsets.UTF_8), clazz);
    }
}
