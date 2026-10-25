package ru.smart.factoring.backend.factoring.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки LLM (Р-12): причины оценки простым языком и черновик сообщения клиенту (п. 5.3).
 *
 * @param mode     stub - шаблонные тексты без LLM, gigachat - GigaChat, off - без текстов (карточка без черновика)
 * @param gigachat настройки GigaChat для mode = gigachat
 */
@ConfigurationProperties("smart-factoring.llm")
public record LlmProperties(String mode, GigaChat gigachat) {

    /**
     * @param authKey  ключ авторизации (Base64 client_id:client_secret) из личного кабинета GigaChat API
     * @param scope    GIGACHAT_API_PERS, GIGACHAT_API_B2B или GIGACHAT_API_CORP
     * @param authUrl  адрес выдачи токена OAuth
     * @param apiUrl   адрес API
     * @param model    модель, например GigaChat
     * @param timeout  таймаут одного запроса
     */
    public record GigaChat(String authKey, String scope, String authUrl, String apiUrl, String model, Duration timeout) {
    }
}
