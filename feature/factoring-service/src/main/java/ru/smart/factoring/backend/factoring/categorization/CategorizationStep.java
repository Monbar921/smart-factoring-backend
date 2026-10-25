package ru.smart.factoring.backend.factoring.categorization;

import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunStep;

import java.util.Map;

/**
 * Блок 3. Категоризация дебиторки.
 */
@Component
public class CategorizationStep implements RunStep {
    private static final int STEP_NO = 3;

    @Override
    public int stepNo() {
        return STEP_NO;
    }

    @Override
    public String name() {
        return "Категоризация дебиторки";
    }

    @Override
    public Result execute(final RunContext context) {
        return Result.done(Map.of());
    }
}
