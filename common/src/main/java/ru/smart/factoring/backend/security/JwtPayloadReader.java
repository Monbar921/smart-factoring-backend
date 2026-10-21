package ru.smart.factoring.backend.security;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Достаёт payload из заголовка "Authorization: Bearer header.payload.signature" без проверки подписи:
 * токен уже проверен gateway, см. {@link JwtPayload}.
 */
@Component
public class JwtPayloadReader {
    private static final String BEARER_PREFIX = "Bearer ";
    /** header.payload.signature */
    private static final int JWT_PARTS = 3;
    private static final int PAYLOAD_PART = 1;
    private static final TypeReference<Map<String, Object>> CLAIMS = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;

    public JwtPayloadReader(final JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * @param authorization значение заголовка Authorization, может быть null
     * @return payload или пусто, если заголовка нет или в нём не JWT
     */
    public Optional<JwtPayload> read(final String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        final String[] parts = authorization.substring(BEARER_PREFIX.length()).trim().split("\\.");
        if (parts.length != JWT_PARTS) {
            return Optional.empty();
        }
        try {
            final byte[] payload = Base64.getUrlDecoder().decode(parts[PAYLOAD_PART]);
            // не Map.copyOf: claims со значением null в токене бывают, а copyOf на них падает
            return Optional.of(new JwtPayload(Collections.unmodifiableMap(jsonMapper.readValue(payload, CLAIMS))));
        } catch (final IllegalArgumentException | JacksonException e) {
            return Optional.empty();
        }
    }
}
