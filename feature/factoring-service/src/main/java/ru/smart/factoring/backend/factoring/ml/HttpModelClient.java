package ru.smart.factoring.backend.factoring.ml;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * Клиент сервиса smart-factoring-ml. JSON сервиса - в snake_case (контракт ml-service-openapi.yaml).
 */
public class HttpModelClient implements ModelClient {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient client;
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public HttpModelClient(final ModelProperties properties) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        final Duration timeout = properties.timeout() != null ? properties.timeout() : DEFAULT_TIMEOUT;
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public Versions versions() {
        final String body = client.get().uri("/v1/models").retrieve().body(String.class);
        final ModelsInfo info = mapper.readValue(body, ModelsInfo.class);
        return new Versions(info.pd().version(), info.delay().version(),
                Boolean.TRUE.equals(info.pd().stub()) || Boolean.TRUE.equals(info.delay().stub()));
    }

    @Override
    public PdResponse scorePd(final PdRequest request) {
        return post("/v1/pd/score", request, PdResponse.class);
    }

    @Override
    public DelayResponse scoreDelay(final DelayRequest request) {
        return post("/v1/delay/score", request, DelayResponse.class);
    }

    private <T> T post(final String path, final Object request, final Class<T> type) {
        final String body = client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(request))
                .retrieve()
                .body(String.class);
        return mapper.readValue(body, type);
    }

    private record ModelVersion(String version, Boolean stub) {
    }

    private record ModelsInfo(ModelVersion pd, ModelVersion delay) {
    }
}
