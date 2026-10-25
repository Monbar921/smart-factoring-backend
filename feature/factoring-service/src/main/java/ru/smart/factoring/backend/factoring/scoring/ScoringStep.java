package ru.smart.factoring.backend.factoring.scoring;

import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunStep;

import java.util.Map;

/**
 * Блок 4. Скоринг покупателей.
 */
@Component
public class ScoringStep implements RunStep {
    private static final int STEP_NO = 4;

    @Override
    public int stepNo() {
        return STEP_NO;
    }

    @Override
    public String name() {
        return "Скоринг покупателей";
    }

    @Override
    public Result execute(final RunContext context) {
        return Result.done(Map.of());
    }
}
