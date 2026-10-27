package ru.smart.factoring.backend.factoring.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ручная проверка настоящего GigaChat API: токен, TLS с сертификатом НУЦ Минцифры и один ответ.
 * Запуск (из каталога модуля factoring-service):
 * GIGACHAT_AUTH_KEY=... GIGACHAT_CA_CERT=/path/russian_trusted_root_ca.pem [GIGACHAT_SCOPE=GIGACHAT_API_PERS]
 * mvn test -Dtest=GigaChatManualTest
 */
@EnabledIfEnvironmentVariable(named = "GIGACHAT_AUTH_KEY", matches = ".+")
class GigaChatManualTest {

    @Test
    void answersInRussian() {
        final String scope = System.getenv().getOrDefault("GIGACHAT_SCOPE", "GIGACHAT_API_PERS");
        final GigaChatClient client = new GigaChatClient(new LlmProperties.GigaChat(System.getenv("GIGACHAT_AUTH_KEY"), scope,
                "https://ngw.devices.sberbank.ru:9443/api/v2/oauth", "https://api.giga.chat/v1",
                System.getenv().getOrDefault("GIGACHAT_MODEL", "GigaChat-2"), Duration.ofSeconds(60), System.getenv("GIGACHAT_CA_CERT")), 1);

        final LlmClient.Completion completion = client.complete(new LlmClient.CompletionRequest(List.of(
                new LlmClient.Message("system", "Отвечай одним коротким предложением."),
                new LlmClient.Message("user", "Что такое факторинг?")), 0.2, 100));

        System.out.println("GIGACHAT " + completion.model() + " " + completion.usage() + ": " + completion.content());
        assertThat(completion.content()).isNotBlank();
    }
}
