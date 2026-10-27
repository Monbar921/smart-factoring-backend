package ru.smart.factoring.backend.api.manualupload;

/**
 * Итог импорта по таблице.
 *
 * @param rows      строк в файле
 * @param inserted  добавлено новых
 * @param updated   изменено существующих
 * @param unchanged совпали с уже загруженными
 * @param conflicts пропущено: тот же ключ, но изменилось неизменяемое поле
 */
public record ManualUploadTableStatsDto(int rows, int inserted, int updated, int unchanged, int conflicts) {
}
