package ru.smart.factoring.backend.api.common;

/**
 * Ссылка на клиента или покупателя в ответах.
 */
public record ClientRefDto(Long id, String inn, String name) {
}
