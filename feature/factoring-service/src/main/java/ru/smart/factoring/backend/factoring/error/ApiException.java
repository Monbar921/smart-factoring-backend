package ru.smart.factoring.backend.factoring.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Ошибка, которая отдаётся клиенту API в формате ErrorDto (docs/claude/module-0/backend-api.md).
 */
@Getter
public class ApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public ApiException(final String code, final HttpStatus status, final String message, final Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public static ApiException notFound(final String what, final Object id) {
        return new ApiException("NOT_FOUND", HttpStatus.NOT_FOUND, what + " " + id + " не найден", Map.of("id", id));
    }

    public static ApiException validation(final String message) {
        return new ApiException("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, message, Map.of());
    }
}
