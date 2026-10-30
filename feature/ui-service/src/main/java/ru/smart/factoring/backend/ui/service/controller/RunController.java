package ru.smart.factoring.backend.ui.service.controller;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.smart.factoring.backend.api.common.PageDto;
import ru.smart.factoring.backend.api.details.FunnelStageDto;
import ru.smart.factoring.backend.api.details.RunBuyerDto;
import ru.smart.factoring.backend.api.details.RunClientDto;
import ru.smart.factoring.backend.api.details.RunUpdDto;
import ru.smart.factoring.backend.api.details.UpdTraceDto;
import ru.smart.factoring.backend.api.run.InitiatorType;
import ru.smart.factoring.backend.api.run.RunAttemptDto;
import ru.smart.factoring.backend.api.run.RunDefaultsDto;
import ru.smart.factoring.backend.api.run.RunDto;
import ru.smart.factoring.backend.api.run.RunListItemDto;
import ru.smart.factoring.backend.api.run.RunLogEntryDto;
import ru.smart.factoring.backend.api.run.StartRunRequest;
import ru.smart.factoring.backend.api.run.StartRunResponse;
import ru.smart.factoring.backend.factoring.details.RunDetailsService;
import ru.smart.factoring.backend.factoring.run.RunRepository;
import ru.smart.factoring.backend.factoring.run.RunService;
import ru.smart.factoring.backend.security.JwtPayload;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Экран процесса: запуск прогона, ход, история, журнал (ЭП-1…ЭП-9).
 */
@Validated
@RestController
@RequestMapping("/internal/rest/runs")
@RequiredArgsConstructor
public class RunController {
    /** роли, которые показываются в истории прогонов (П4-6) */
    private static final List<String> SHOWN_ROLES = List.of("operator", "manager");

    private final RunService runService;
    private final RunDetailsService runDetailsService;
    /** клиент Keycloak гостевого входа «Запуск демо»: его токен - только просмотр (П4-1) */
    @Value("${smart-factoring.guest-client-id:smart-factoring-guest}")
    private String guestClientId;

    @GetMapping("/defaults")
    public RunDefaultsDto defaults() {
        return runService.defaults();
    }

    /**
     * Запуск прогона (ПР-2). Заголовок X-Initiator-Type: API - запуск вызовом API, иначе - оператором с экрана.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartRunResponse start(final @Nullable @RequestBody(required = false) StartRunRequest request,
                                  final @Nullable @RequestHeader(value = "X-Initiator-Type", required = false) InitiatorType type,
                                  final @Nullable JwtPayload user) {
        final StartRunRequest body = request != null ? request : new StartRunRequest(null, null, null);
        final InitiatorType initiatorType = type != null ? type : InitiatorType.OPERATOR;
        return new StartRunResponse(runService.start(body, initiatorType, initiator(user)));
    }

    /**
     * Попытки запуска прогона, и отклонённые (П4-6).
     */
    @GetMapping("/attempts")
    public PageDto<RunAttemptDto> attempts(@RequestParam(defaultValue = "0") final int page,
                                           @RequestParam(defaultValue = "50") final int size) {
        return runService.attempts(page, size);
    }

    private RunRepository.Initiator initiator(final @Nullable JwtPayload user) {
        if (user == null) {
            return RunRepository.Initiator.NONE;
        }
        if (guestClientId.equals(user.authorizedParty())) {
            return new RunRepository.Initiator(user.username(), "гость", true);
        }
        final String roles = user.roles().stream().filter(SHOWN_ROLES::contains).sorted().collect(Collectors.joining(", "));
        return new RunRepository.Initiator(user.username(), roles.isEmpty() ? null : roles, false);
    }

    @GetMapping
    public PageDto<RunListItemDto> list(@RequestParam(defaultValue = "0") final int page,
                                        @RequestParam(defaultValue = "50") final int size) {
        return runService.list(page, size);
    }

    @GetMapping("/{runId}")
    public RunDto get(@PathVariable final long runId) {
        return runService.get(runId);
    }

    @GetMapping("/{runId}/log")
    public PageDto<RunLogEntryDto> log(@PathVariable final long runId,
                                       @RequestParam(required = false) final String level,
                                       @RequestParam(required = false) final Integer stepNo,
                                       @RequestParam(defaultValue = "0") final int page,
                                       @RequestParam(defaultValue = "100") final int size) {
        return runService.log(runId, level, stepNo, page, size);
    }

    /**
     * Воронка УПД (ЭП-4).
     */
    @GetMapping("/{runId}/funnel")
    public List<FunnelStageDto> funnel(@PathVariable final long runId) {
        return runDetailsService.funnel(runId);
    }

    /**
     * Детализация УПД прогона с фильтрами (ЭП-5).
     */
    @GetMapping("/{runId}/upds")
    @SuppressWarnings("checkstyle:ParameterNumber")
    public PageDto<RunUpdDto> upds(@PathVariable final long runId,
                                   @RequestParam(required = false) final Long clientId,
                                   @RequestParam(required = false) final Long buyerId,
                                   @RequestParam(required = false) final Integer category,
                                   @RequestParam(required = false) final String decision,
                                   @RequestParam(required = false) final String reason,
                                   @RequestParam(required = false) final String stage,
                                   @RequestParam(required = false) final String riskLevel,
                                   @RequestParam(required = false) final String paymentStatus,
                                   @RequestParam(defaultValue = "0") final int page,
                                   @RequestParam(defaultValue = "50") final int size) {
        return runDetailsService.upds(runId, clientId, buyerId, category, decision, reason, stage, riskLevel, paymentStatus, page, size);
    }

    /**
     * Путь УПД через шаги прогона (ЭП-5).
     */
    @GetMapping("/{runId}/upds/{updId}/trace")
    public UpdTraceDto trace(@PathVariable final long runId, @PathVariable final long updId) {
        return runDetailsService.trace(runId, updId);
    }

    @GetMapping("/{runId}/buyers")
    public List<RunBuyerDto> buyers(@PathVariable final long runId) {
        return runDetailsService.buyers(runId);
    }

    @GetMapping("/{runId}/clients")
    public List<RunClientDto> clients(@PathVariable final long runId) {
        return runDetailsService.clients(runId);
    }
}
