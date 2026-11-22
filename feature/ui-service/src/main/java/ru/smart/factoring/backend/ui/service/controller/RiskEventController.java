package ru.smart.factoring.backend.ui.service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.smart.factoring.backend.api.risk.EfrsbEventRequest;
import ru.smart.factoring.backend.api.risk.RiskBuyerDto;
import ru.smart.factoring.backend.api.risk.RiskReactionDto;
import ru.smart.factoring.backend.factoring.risk.RiskEventService;

import java.util.List;

/**
 * Имитация подписки на мониторинг контрагентов (Р-86): событие ЕФРСБ по покупателю между прогонами. Путь - под
 * ручной загрузкой: в gateway он доступен только оператору, как и импорт данных.
 */
@RestController
@RequestMapping("/internal/rest/manual-upload/efrsb-event")
@RequiredArgsConstructor
public class RiskEventController {
    private final RiskEventService riskEventService;

    /**
     * Покупатели с предложениями в очереди или переданными в факторинг.
     */
    @GetMapping("/buyers")
    public List<RiskBuyerDto> buyers() {
        return riskEventService.activeBuyers();
    }

    /**
     * Новое событие: запись в источники ЕФРСБ / ЕГРЮЛ и немедленная реакция - отзыв предложений, сигнал в риск,
     * уведомления менеджерам.
     */
    @PostMapping
    public RiskReactionDto receive(@RequestBody final EfrsbEventRequest request) {
        return riskEventService.receive(request);
    }
}
