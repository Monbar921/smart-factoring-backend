package ru.smart.factoring.backend.api.run;

/**
 * Кто запустил прогон (НФР-7).
 * <ul>
 *     <li>OPERATOR - оператор с экрана процесса;</li>
 *     <li>API - вызов API (заголовок X-Initiator-Type);</li>
 *     <li>SCHEDULE - ночной прогон по расписанию (Р-85); сам прогон удалён, значение оставлено для уже
 *     сохранённых запусков (ограничение run_initiator_type_check его допускает).</li>
 * </ul>
 */
public enum InitiatorType {
    OPERATOR,
    API,
    SCHEDULE
}
