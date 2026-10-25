package ru.smart.factoring.backend.api.crm;

/**
 * Менеджер в эмуляторе CRM (ЭП-7).
 */
public record ManagerDto(long id, String code, String fullName, int queuedOffers, int notifications) {
}
