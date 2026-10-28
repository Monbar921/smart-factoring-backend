package ru.smart.factoring.backend.api.run;

import ru.smart.factoring.backend.api.common.ClientRefDto;

import java.time.LocalDate;
import java.util.List;

/**
 * Значения формы запуска (ЭП-1).
 *
 * @param dataDate       последняя дата УПД или платежа в источниках - дата расчёта по умолчанию
 * @param snapshotDate   дата снимка внешних данных по покупателям
 * @param clients        клиенты, доступные для прогона
 * @param runningRunId   прогон, который выполняется сейчас, иначе null
 * @param batches        партии ручной загрузки с их клиентами (Р-62); клиенты выгрузки генератора - без партии
 */
public record RunDefaultsDto(LocalDate dataDate, LocalDate snapshotDate, List<ClientRefDto> clients, Long runningRunId,
                             List<RunBatchDto> batches) {
}
