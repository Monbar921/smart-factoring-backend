package ru.smart.factoring.backend.factoring.reconciliation;

import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunStep;

import java.util.Map;

/**
 * Блок 2. Сверка оплат.
 */
@Component
public class ReconciliationStep implements RunStep {
    private static final int STEP_NO = 2;

    @Override
    public int stepNo() {
        return STEP_NO;
    }

    @Override
    public String name() {
        return "Сверка оплат";
    }

    @Override
    public Result execute(final RunContext context) {
        return Result.done(Map.of());
    }
}
