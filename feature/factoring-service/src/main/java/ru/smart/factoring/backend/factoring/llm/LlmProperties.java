package ru.smart.factoring.backend.factoring.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки LLM (Р-12): причины оценки простым языком и черновик сообщения клиенту (п. 5.3),
 * а также запросы сервиса моделей через backend (Р-48).
 *
 * @param mode        stub - шаблонные тексты без LLM, gigachat - GigaChat, off - без текстов (карточка без черновика)
 * @param gigachat    настройки GigaChat для mode = gigachat
 * @param parallelism одновременных запросов к LLM: и потоков прогона, и предел одновременных запросов к GigaChat
 */
@ConfigurationProperties("smart-factoring.llm")
public record LlmProperties(String mode, GigaChat gigachat, Integer parallelism) {

    /**
     * @param authKey  ключ авторизации (Base64 client_id:client_secret) из личного кабинета GigaChat API
     * @param scope    GIGACHAT_API_PERS, GIGACHAT_API_B2B или GIGACHAT_API_CORP
     * @param authUrl  адрес выдачи токена OAuth
     * @param apiUrl   адрес API
     * @param model    поле model запроса: GigaChat (Lite), GigaChat-2-Pro, GigaChat-2-Max, GigaChat-3-Ultra
     * @param timeout  таймаут одного запроса
     * @param caCert   PEM-файл с корневым сертификатом НУЦ Минцифры; пусто - только сертификаты JDK
     */
    public record GigaChat(String authKey, String scope, String authUrl, String apiUrl, String model, Duration timeout,
                           String caCert) {
    }
}
