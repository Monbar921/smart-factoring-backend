package ru.smart.factoring.backend.factoring.decision;

import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunStep;

import java.util.Map;

/**
 * Блок 5. Решение и предложение.
 */
@Component
public class DecisionStep implements RunStep {
    private static final int STEP_NO = 5;

    @Override
    public int stepNo() {
        return STEP_NO;
    }

    @Override
    public String name() {
        return "Решение и предложение";
    }

    @Override
    public Result execute(final RunContext context) {
        return Result.done(Map.of());
    }
}
