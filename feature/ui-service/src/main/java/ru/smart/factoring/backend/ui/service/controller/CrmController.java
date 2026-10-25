package ru.smart.factoring.backend.ui.service.controller;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.smart.factoring.backend.api.common.PageDto;
import ru.smart.factoring.backend.api.crm.ClientCardDto;
import ru.smart.factoring.backend.api.crm.ManagerDto;
import ru.smart.factoring.backend.api.crm.NotificationDto;
import ru.smart.factoring.backend.api.crm.OfferActionResultDto;
import ru.smart.factoring.backend.api.crm.OfferDto;
import ru.smart.factoring.backend.api.crm.RejectOfferRequest;
import ru.smart.factoring.backend.factoring.offer.CrmService;
import ru.smart.factoring.backend.factoring.offer.OfferDecisionService;
import ru.smart.factoring.backend.security.JwtPayload;

import java.util.List;

/**
 * Эмулятор CRM: очередь, карточка клиента, уведомления (ЭП-7, п. 5.5) и решения менеджера (блок 6).
 */
@Validated
@RestController
@RequestMapping("/internal/rest")
@RequiredArgsConstructor
public class CrmController {
    private final CrmService crmService;
    private final OfferDecisionService offerDecisionService;

    @GetMapping("/managers")
    public List<ManagerDto> managers() {
        return crmService.managers();
    }

    @GetMapping("/managers/{managerId}/queue")
    public List<OfferDto> queue(@PathVariable final long managerId) {
        return crmService.queue(managerId);
    }

    @GetMapping("/managers/{managerId}/notifications")
    public PageDto<NotificationDto> notifications(@PathVariable final long managerId,
                                                  @RequestParam(required = false) final String type,
                                                  @RequestParam(defaultValue = "0") final int page,
                                                  @RequestParam(defaultValue = "100") final int size) {
        return crmService.notifications(managerId, type, page, size);
    }

    @GetMapping("/clients/{clientId}/card")
    public ClientCardDto card(@PathVariable final long clientId) {
        return crmService.card(clientId);
    }

    /**
     * «Передать в факторинг» (п. 6.2).
     */
    @PostMapping("/offers/{offerId}/transfer")
    public OfferActionResultDto transfer(@PathVariable final long offerId, final @Nullable JwtPayload user) {
        return offerDecisionService.transfer(offerId, user != null ? user.username() : null);
    }

    /**
     * «Отклонить» (п. 6.3).
     */
    @PostMapping("/offers/{offerId}/reject")
    public OfferActionResultDto reject(@PathVariable final long offerId,
                                       final @Nullable @RequestBody(required = false) RejectOfferRequest request,
                                       final @Nullable JwtPayload user) {
        return offerDecisionService.reject(offerId, user != null ? user.username() : null, request != null ? request.comment() : null);
    }
}
