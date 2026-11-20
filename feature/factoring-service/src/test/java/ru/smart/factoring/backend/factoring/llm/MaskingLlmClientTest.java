package ru.smart.factoring.backend.factoring.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Последний рубеж маскирования (Р-73): ИНН и ОГРН в любом запросе к LLM уходят метками и возвращаются в ответе.
 */
class MaskingLlmClientTest {

    @Test
    void innAndOgrnAreMaskedAndRestored() {
        final AtomicReference<LlmClient.CompletionRequest> sent = new AtomicReference<>();
        final LlmClient client = new MaskingLlmClient(echo(sent));

        final LlmClient.Completion completion = client.complete(new LlmClient.CompletionRequest(List.of(
                new LlmClient.Message("system", "Ты аналитик"),
                new LlmClient.Message("user", "Покупатель ИНН 7701234567, ОГРН 1027700132195, поставщик ИНН 770123456789; "
                        + "ещё раз 7701234567; сумма 4 500 000 руб., телефон 8 800 100-24-24")), 0.2, 100));

        final String user = sent.get().messages().get(1).content();
        assertThat(user)
                .doesNotContain("7701234567", "1027700132195", "770123456789")
                .contains("ИНН ID_1, ОГРН ID_2, поставщик ИНН ID_3; ещё раз ID_1")
                .contains("4 500 000 руб.", "8 800 100-24-24");
        assertThat(completion.content()).isEqualTo("Ответ: " + "Покупатель ИНН 7701234567, ОГРН 1027700132195, поставщик ИНН "
                + "770123456789; ещё раз 7701234567; сумма 4 500 000 руб., телефон 8 800 100-24-24");
    }

    @Test
    void requestWithoutIdentifiersIsUnchanged() {
        final AtomicReference<LlmClient.CompletionRequest> sent = new AtomicReference<>();
        final LlmClient.CompletionRequest request = new LlmClient.CompletionRequest(
                List.of(new LlmClient.Message("user", "Покупатель BUYER_1, срок 40 дн.")), null, null, "model-x");

        new MaskingLlmClient(echo(sent)).complete(request);

        assertThat(sent.get()).isEqualTo(request);
    }

    /**
     * LLM, отвечающая последним сообщением запроса.
     */
    private static LlmClient echo(final AtomicReference<LlmClient.CompletionRequest> sent) {
        return new LlmClient() {
            @Override
            public String model() {
                return "echo";
            }

            @Override
            public Completion complete(final CompletionRequest request) {
                sent.set(request);
                return new Completion("Ответ: " + request.messages().getLast().content(), "echo", "stop", new Usage(1, 1, 2));
            }

            @Override
            public List<String> models() {
                return List.of("echo");
            }
        };
    }
}
