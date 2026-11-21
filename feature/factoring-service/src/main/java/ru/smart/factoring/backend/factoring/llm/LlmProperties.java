package ru.smart.factoring.backend.factoring.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки LLM (Р-12): причины оценки простым языком и черновик сообщения клиенту (п. 5.3),
 * а также запросы сервиса моделей через backend (Р-48).
 *
 * @param mode        stub - шаблонные тексты без LLM, gigachat - GigaChat, onprem - LLM в контуре банка с API вида OpenAI
 *                    (Р-74), off - без текстов (карточка без черновика)
 * @param mask        true - названия клиента и покупателя, ИНН и номер УПД уходят в LLM метками и подставляются обратно
 *                    в ответ (Р-73); null - true
 * @param gigachat    настройки GigaChat для mode = gigachat
 * @param onprem      настройки on-prem LLM для mode = onprem
 * @param parallelism одновременных запросов к LLM: и потоков прогона, и предел одновременных запросов к LLM
 * @param maxTokens   предел токенов ответа на текст карточки; null - 512
 */
@ConfigurationProperties("smart-factoring.llm")
public record LlmProperties(String mode, Boolean mask, GigaChat gigachat, OnPrem onprem, Integer parallelism, Integer maxTokens) {

    private static final int DEFAULT_MAX_TOKENS = 512;

    /**
     * Предел токенов ответа на один текст карточки; моделям с «мышлением» нужно больше - рассуждение тоже в пределе.
     */
    public int answerTokens() {
        return maxTokens != null && maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
    }

    /**
     * Маскировать данные клиентов перед LLM; по умолчанию - да.
     */
    public boolean masked() {
        return mask == null || mask;
    }

    /**
     * @param authKey  ключ авторизации (Base64 client_id:client_secret) из личного кабинета GigaChat API
     * @param scope    GIGACHAT_API_PERS, GIGACHAT_API_B2B или GIGACHAT_API_CORP
     * @param authUrl  адрес выдачи токена OAuth
     * @param apiUrl   адрес API
     * @param model    поле model запроса: GigaChat-2 (по умолчанию), GigaChat-2-Pro, GigaChat-2-Max, GigaChat-3-Pro...
     * @param timeout  таймаут одного запроса
     * @param caCert   PEM-файл с корневым сертификатом НУЦ Минцифры; пусто - только сертификаты JDK
     */
    public record GigaChat(String authKey, String scope, String authUrl, String apiUrl, String model, Duration timeout,
                           String caCert) {
    }

    /**
     * @param apiUrl  адрес API вида OpenAI до /chat/completions, например http://llm:8000/v1 (vLLM) или http://ollama:11434/v1
     * @param apiKey  ключ, если сервер его требует; пусто - без авторизации
     * @param model   имя модели на сервере (GET /models)
     * @param timeout таймаут одного запроса
     * @param caCert  PEM-файл с корневым сертификатом корпоративного УЦ; пусто - только сертификаты JDK
     */
    public record OnPrem(String apiUrl, String apiKey, String model, Duration timeout, String caCert) {
    }
}
