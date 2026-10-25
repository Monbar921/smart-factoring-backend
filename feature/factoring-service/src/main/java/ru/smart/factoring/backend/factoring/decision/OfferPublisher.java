package ru.smart.factoring.backend.factoring.decision;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.factoring.json.Json;
import ru.smart.factoring.backend.factoring.model.UpdState;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunLog;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Публикация очереди менеджера (п. 5.3, пункты 4-6; п. 3.9; ПР-8; Р-32): одной транзакцией в конце шага 5.
 * <ul>
 *     <li>предложение по УПД, которое уже в очереди, обновляется (не дублируется), новое - создаётся;</li>
 *     <li>предложение в очереди, которого нет в этом прогоне, отзывается: УПД оплачен, просрочен или больше не проходит;</li>
 *     <li>УПД с решением менеджера, принятым во время прогона, в очередь не попадает (ПР-8);</li>
 *     <li>уведомление о новом предложении выше порога (п. 5.4) и напоминание о необработанном (п. 5.3, Р-40).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class OfferPublisher {
    private final NamedParameterJdbcTemplate jdbc;
    private final DecisionRepository repository;
    private final RunLog runLog;
    private final Json json;

    public Published publish(final RunContext context, final List<Offer> offers, final List<UpdState> states,
                             final List<Long> clientIds) {
        final long runId = context.getRunId();
        final Map<Long, DecisionRepository.QueuedOffer> queued = new HashMap<>();
        repository.queuedOffers(clientIds).forEach(offer -> queued.put(offer.updId(), offer));
        // ПР-8: решение менеджера могло прийти во время прогона - перечитываем отметки перед публикацией
        final Set<Long> decided = repository.decidedUpdIds(offers.stream().map(offer -> offer.decision().state().upd().id()).toList());
        final Map<Long, UpdState> stateByUpd = new HashMap<>();
        states.forEach(state -> stateByUpd.put(state.upd().id(), state));

        final List<RunLog.Entry> log = new ArrayList<>();
        final List<MapSqlParameterSource> notifications = new ArrayList<>();
        final List<Long> published = new ArrayList<>();
        final Set<Long> newUpdIds = new HashSet<>();
        int reminders = 0;
        for (final Offer offer : offers) {
            final DecisionEngine.Decision decision = offer.decision();
            final long updId = decision.state().upd().id();
            if (decided.contains(updId)) {
                log.add(entry(runId, "SKIPPED_DECIDED", "УПД " + decision.state().upd().number()
                        + ": решение менеджера принято во время прогона, в очередь не попадает (ПР-8)", decision));
                continue;
            }
            final DecisionRepository.QueuedOffer existing = queued.remove(updId);
            final MapSqlParameterSource params = params(runId, offer, existing == null);
            final long offerId;
            if (existing != null) {
                params.addValue("id", existing.id());
                jdbc.update("""
                        update offer set last_run_id = :runId, is_new = false, ead = :ead, price = :price, price_rate = :priceRate,
                            term_days = :termDays, income = :income, el = :el, risk_level = :riskLevel, pd = :pd, rank_value = :rank,
                            flags = cast(:flags as jsonb), reasons = cast(:reasons as jsonb), reasons_text = :reasonsText,
                            message_draft = :messageDraft, updated_at = now()
                        where id = :id""", params);
                offerId = existing.id();
                if (ChronoUnit.DAYS.between(existing.createdCalcDate(), context.getCalcDate()) >= context.getParameters().reminderDays()) {
                    reminders++;
                    notifications.add(notification(runId, "REMINDER", offerId, decision, Map.of(
                            "updNumber", decision.state().upd().number(),
                            "daysInQueue", ChronoUnit.DAYS.between(existing.createdCalcDate(), context.getCalcDate()))));
                }
            } else {
                offerId = jdbc.queryForObject("""
                        insert into offer (upd_id, client_id, buyer_id, manager_id, status, created_run_id, last_run_id, is_new, ead,
                            price, price_rate, term_days, income, el, risk_level, pd, rank_value, flags, reasons, reasons_text, message_draft)
                        values (:updId, :clientId, :buyerId, :managerId, 'QUEUED', :runId, :runId, true, :ead, :price, :priceRate,
                            :termDays, :income, :el, :riskLevel, :pd, :rank, cast(:flags as jsonb), cast(:reasons as jsonb),
                            :reasonsText, :messageDraft)
                        returning id""", params, Long.class);
                newUpdIds.add(updId);
                if (decision.finalEconomics().ead().compareTo(context.getParameters().newOfferNotificationThreshold()) > 0) {
                    notifications.add(notification(runId, "NEW_OFFER", offerId, decision, Map.of(
                            "updNumber", decision.state().upd().number(),
                            "ead", decision.finalEconomics().ead())));
                }
            }
            published.add(offerId);
        }

        // п. 3.9 и ПР-8: предложения в очереди по УПД клиентов прогона, которых в этом прогоне нет, - отзываются
        int revoked = 0;
        for (final DecisionRepository.QueuedOffer offer : queued.values()) {
            final UpdState state = stateByUpd.get(offer.updId());
            final String reason = revokeReason(state);
            jdbc.update("update offer set status = 'REVOKED', revoke_reason = :reason, last_run_id = :runId, updated_at = now() where id = :id",
                    new MapSqlParameterSource().addValue("reason", reason).addValue("runId", runId).addValue("id", offer.id()));
            log.add(RunLog.Entry.of(runId, RunLog.Level.INFO, DecisionStep.STEP_NO, "OFFER_REVOKED",
                    "Предложение " + offer.id() + " отозвано: " + reason).upd(offer.updId()).offer(offer.id()));
            revoked++;
        }

        jdbc.batchUpdate("""
                        insert into notification (run_id, manager_id, type, client_id, buyer_id, upd_id, offer_id, payload)
                        values (:runId, :managerId, :type, :clientId, :buyerId, :updId, :offerId, cast(:payload as jsonb))""",
                notifications.toArray(MapSqlParameterSource[]::new));
        runLog.write(log);
        return new Published(published.size(), newUpdIds, revoked, reminders,
                (int) notifications.stream().filter(params -> "NEW_OFFER".equals(params.getValue("type"))).count());
    }

    /**
     * УПД нет среди УПД прогона (дата расчёта сдвинута назад раньше его подписания) - больше не проходит.
     */
    private static String revokeReason(final UpdState state) {
        if (state == null) {
            return "NO_LONGER_ELIGIBLE";
        }
        if (!state.open()) {
            return "UPD_PAID";
        }
        return state.overdue() ? "UPD_OVERDUE" : "NO_LONGER_ELIGIBLE";
    }

    private MapSqlParameterSource params(final long runId, final Offer offer, final boolean isNew) {
        final DecisionEngine.Decision decision = offer.decision();
        final Economics.Result economics = decision.finalEconomics();
        final Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("delayProbable", decision.delayProbable());
        flags.put("lowData", decision.buyer().lowData());
        flags.put("reducedToLimit", decision.reducedToLimit());
        flags.put("isNew", isNew);
        return new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("updId", decision.state().upd().id())
                .addValue("clientId", decision.client().id())
                .addValue("buyerId", decision.state().upd().buyerId())
                .addValue("managerId", decision.client().managerId())
                .addValue("ead", economics.ead())
                .addValue("price", economics.price())
                .addValue("priceRate", economics.priceRate())
                .addValue("termDays", economics.termDays())
                .addValue("income", economics.income())
                .addValue("el", economics.el())
                .addValue("riskLevel", decision.buyer().riskLevel().name())
                .addValue("pd", decision.buyer().pd())
                .addValue("rank", decision.rank())
                .addValue("flags", json.write(flags))
                .addValue("reasons", json.write(decision.buyer().reasons()))
                .addValue("reasonsText", offer.texts().reasonsText())
                .addValue("messageDraft", offer.texts().messageDraft());
    }

    private MapSqlParameterSource notification(final long runId, final String type, final long offerId,
                                               final DecisionEngine.Decision decision, final Map<String, Object> payload) {
        return new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("managerId", decision.client().managerId())
                .addValue("type", type)
                .addValue("clientId", decision.client().id())
                .addValue("buyerId", decision.state().upd().buyerId())
                .addValue("updId", decision.state().upd().id())
                .addValue("offerId", offerId)
                .addValue("payload", json.write(payload));
    }

    private static RunLog.Entry entry(final long runId, final String code, final String message, final DecisionEngine.Decision decision) {
        return RunLog.Entry.of(runId, RunLog.Level.INFO, DecisionStep.STEP_NO, code, message)
                .client(decision.client().id())
                .buyer(decision.state().upd().buyerId())
                .upd(decision.state().upd().id());
    }

    /**
     * Предложение к публикации: решение блока 5 и тексты LLM.
     */
    public record Offer(DecisionEngine.Decision decision, Explainer.Texts texts) {
    }

    /**
     * Итог публикации.
     *
     * @param newUpdIds УПД, по которым предложение появилось в этом прогоне («новое в прогоне», Р-16)
     */
    public record Published(int queued, Set<Long> newUpdIds, int revoked, int reminders, int newOfferNotifications) {
    }
}
