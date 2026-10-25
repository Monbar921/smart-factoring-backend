package ru.smart.factoring.backend.factoring.json;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Запись и чтение jsonb-колонок.
 */
@Component
@RequiredArgsConstructor
public class Json {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;

    public String write(final Object value) {
        return value == null ? null : jsonMapper.writeValueAsString(value);
    }

    public Map<String, Object> readMap(final String json) {
        return json == null ? null : jsonMapper.readValue(json, MAP);
    }

    public <T> T read(final String json, final Class<T> type) {
        return json == null ? null : jsonMapper.readValue(json, type);
    }

    public <T> T read(final String json, final TypeReference<T> type) {
        return json == null ? null : jsonMapper.readValue(json, type);
    }
}
