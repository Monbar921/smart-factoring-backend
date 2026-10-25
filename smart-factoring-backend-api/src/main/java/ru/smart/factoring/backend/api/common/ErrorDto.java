package ru.smart.factoring.backend.api.common;

import java.util.Map;

/**
 * Единый формат ошибки API (docs/claude/module-0/backend-api.md).
 */
public record ErrorDto(String code, String message, Map<String, Object> details) {
}
