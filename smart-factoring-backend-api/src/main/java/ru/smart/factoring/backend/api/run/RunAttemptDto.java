package ru.smart.factoring.backend.api.run;

import java.time.OffsetDateTime;

/**
 * Попытка запуска прогона (замечания 30.10, П4-6): кто, с какой ролью, чем кончилась.
 *
 * @param initiator     логин; null - запуск вызовом API без токена
 * @param initiatorRole роли пользователя: operator, manager, «гость»
 * @param outcome       STARTED - прогон запущен, REJECTED - отказ
 * @param reason        причина отказа: FORBIDDEN (нет прав), RUN_ALREADY_DONE (эти данные уже прогнаны),
 *                      RUN_ALREADY_RUNNING (прогон уже идёт), VALIDATION (неверный запрос, нет данных)
 * @param message       текст отказа, как его увидел пользователь
 * @param runId         запущенный прогон; при отказе - прогон, на который он ссылается (прежний результат, идущий прогон)
 */
public record RunAttemptDto(long id,
                            OffsetDateTime attemptedAt,
                            String initiator,
                            String initiatorRole,
                            String outcome,
                            String reason,
                            String message,
                            Long runId) {
}
