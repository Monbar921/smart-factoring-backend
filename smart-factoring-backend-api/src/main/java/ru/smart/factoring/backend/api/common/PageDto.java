package ru.smart.factoring.backend.api.common;

import java.util.List;

/**
 * Страница списка.
 */
public record PageDto<T>(List<T> items, int page, int size, long total) {
}
