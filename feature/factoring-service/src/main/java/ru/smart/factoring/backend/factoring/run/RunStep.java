package ru.smart.factoring.backend.factoring.run;

import java.util.Map;

/**
 * Шаг прогона - один из блоков 1-5 BRD (ПР-5). Исключение из {@link #execute} - сбой шага: прогон останавливается (ПР-6).
 * Сбой отдельного УПД шаг обрабатывает сам и возвращает {@link Result#warning}.
 */
public interface RunStep {

    int stepNo();

    String name();

    Result execute(RunContext context);

    /**
     * Результат шага.
     *
     * @param metrics числа результата для экрана процесса (ЭП-3)
     * @param warning был технический сбой части объектов (статус шага «предупреждение»)
     */
    record Result(Map<String, Object> metrics, boolean warning) {

        public static Result done(final Map<String, Object> metrics) {
            return new Result(metrics, false);
        }

        public static Result warning(final Map<String, Object> metrics) {
            return new Result(metrics, true);
        }
    }
}
