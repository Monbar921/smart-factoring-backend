package ru.smart.factoring.backend.factoring.run;

import lombok.Getter;
import ru.smart.factoring.backend.factoring.parameter.RunParameters;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Состояние одного прогона, общее для его шагов.
 * <p>
 * Шаги сохраняют свои результаты в БД (ПР-5) и дополнительно кладут их сюда ({@link #put}), чтобы следующий шаг
 * не перечитывал их из базы. Итоги для истории прогонов (ЭП-8) - в {@link #summary()}.
 */
@Getter
public class RunContext {
    private final long runId;
    private final LocalDate calcDate;
    /**
     * ИНН клиентов прогона; null - все клиенты (ПР-2, Р-29).
     */
    private final List<String> clientInns;
    private final RunParameters parameters;
    private final Map<String, Object> summary = new ConcurrentHashMap<>();
    private final Map<String, Object> modelVersions = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> attributes = new ConcurrentHashMap<>();
    private final AtomicBoolean warnings = new AtomicBoolean();

    public RunContext(final long runId, final LocalDate calcDate, final List<String> clientInns, final RunParameters parameters) {
        this.runId = runId;
        this.calcDate = calcDate;
        this.clientInns = clientInns;
        this.parameters = parameters;
    }

    public <T> void put(final Class<T> type, final T value) {
        attributes.put(type, value);
    }

    public <T> T get(final Class<T> type) {
        final Object value = attributes.get(type);
        if (value == null) {
            throw new IllegalStateException("В прогоне " + runId + " нет результата " + type.getSimpleName());
        }
        return type.cast(value);
    }

    /**
     * Технический сбой части УПД (НФР-4.1): прогон завершится со статусом «выполнен с предупреждениями».
     */
    public void markWarning() {
        warnings.set(true);
    }

    public boolean hasWarnings() {
        return warnings.get();
    }
}
