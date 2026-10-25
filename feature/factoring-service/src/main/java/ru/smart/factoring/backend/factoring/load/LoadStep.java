package ru.smart.factoring.backend.factoring.load;

import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunStep;

import java.util.Map;

/**
 * Блок 1. Загрузка данных.
 */
@Component
public class LoadStep implements RunStep {
    private static final int STEP_NO = 1;

    @Override
    public int stepNo() {
        return STEP_NO;
    }

    @Override
    public String name() {
        return "Загрузка данных";
    }

    @Override
    public Result execute(final RunContext context) {
        return Result.done(Map.of());
    }
}
