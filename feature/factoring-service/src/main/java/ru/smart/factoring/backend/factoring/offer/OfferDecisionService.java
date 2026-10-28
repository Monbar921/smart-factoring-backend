package ru.smart.factoring.backend.factoring.offer;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.smart.factoring.backend.api.crm.OfferActionResultDto;
import ru.smart.factoring.backend.api.crm.OfferDto;
import ru.smart.factoring.backend.factoring.error.ApiException;
import ru.smart.factoring.backend.factoring.json.Json;
import ru.smart.factoring.backend.factoring.model.UpdNumber;
import ru.smart.factoring.backend.factoring.run.RunLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Блок 6. Решение менеджера после прогона: «Передать в факторинг» или «Отклонить» (п. 6.2-6.4).
 * Решение записывается сразу, не зависит от прогонов и прогоном не меняется (ПР-8).
 */
@Service
@RequiredArgsConstructor
public class OfferDecisionService {
    private final NamedParameterJdbcTemplate jdbc;
    private final OfferQueries offers;
    private final RunLog runLog;
    private final Json json;

    /**
     * «Передать в факторинг»: статус «передано», пакет - в имитацию ВТБ Факторинга (п. 6.2, ДД-1).
     */
    @Transactional
    public OfferActionResultDto transfer(final long offerId, final String user) {
        final OfferDto offer = lockQueued(offerId);
        decide(offerId, "TRANSFERRED", user);
        final Map<String, Object> contract = jdbc.queryForMap("""
                        select k.number, k.contract_date, k.deferral_days from upd u left join contract k on k.id = u.contract_id
                        where u.id = :updId""", new MapSqlParameterSource("updId", offer.updId()));
        final Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("offerId", offerId);
        pack.put("client", offer.client());
        pack.put("buyer", offer.buyer());
        pack.put("upd", Map.of("number", offer.updNumber(), "date", offer.updDate().toString(),
                "shipmentConfirmed", true));
        pack.put("contract", Map.of("number", String.valueOf(contract.get("number")), "date", String.valueOf(contract.get("contract_date")),
                "deferralDays", contract.get("deferral_days")));
        pack.put("ead", offer.ead());
        pack.put("price", offer.price());
        pack.put("priceRate", offer.priceRate());
        pack.put("termDays", offer.termDays());
        pack.put("riskLevel", offer.riskLevel());
        pack.put("pd", offer.pd());
        pack.put("reasons", offer.reasons());
        pack.put("decidedBy", user);
        final Long packageId = jdbc.queryForObject("""
                        insert into imitation_factoring_package (offer_id, payload) values (:offerId, cast(:payload as jsonb)) returning id""",
                new MapSqlParameterSource().addValue("offerId", offerId).addValue("payload", json.write(pack)), Long.class);
        log(offer, "OFFER_TRANSFERRED", "Передано в факторинг: " + UpdNumber.label(offer.updNumber()) + ", " + offer.ead() + " руб., пакет "
                + packageId + " в ВТБ Факторинг (имитация)", user, null);
        return new OfferActionResultDto(offers.byId(offerId).orElseThrow(), packageId);
    }

    /**
     * «Отклонить» (п. 6.3).
     */
    @Transactional
    public OfferActionResultDto reject(final long offerId, final String user, final String comment) {
        final OfferDto offer = lockQueued(offerId);
        decide(offerId, "REJECTED", user);
        log(offer, "OFFER_REJECTED", "Отклонено: " + UpdNumber.label(offer.updNumber())
                + (comment != null && !comment.isBlank() ? ", " + comment : ""), user, comment);
        return new OfferActionResultDto(offers.byId(offerId).orElseThrow(), null);
    }

    private OfferDto lockQueued(final long offerId) {
        final List<String> status = jdbc.queryForList("select status from offer where id = :id for update",
                new MapSqlParameterSource("id", offerId), String.class);
        if (status.isEmpty()) {
            throw ApiException.notFound("Предложение", offerId);
        }
        if (!"QUEUED".equals(status.getFirst())) {
            throw new ApiException("OFFER_NOT_QUEUED", HttpStatus.CONFLICT,
                    "Предложение не в очереди: " + status.getFirst(), Map.of("status", status.getFirst()));
        }
        return offers.byId(offerId).orElseThrow();
    }

    private void decide(final long offerId, final String status, final String user) {
        jdbc.update("""
                        update offer set status = :status, decided_by = :user, decided_at = now(), updated_at = now() where id = :id""",
                new MapSqlParameterSource().addValue("status", status).addValue("user", user != null ? user : "manager")
                        .addValue("id", offerId));
    }

    private void log(final OfferDto offer, final String code, final String message, final String user, final String comment) {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("user", user);
        details.put("ead", offer.ead());
        if (comment != null) {
            details.put("comment", comment);
        }
        runLog.write(List.of(RunLog.Entry.of(null, RunLog.Level.INFO, null, code, message)
                .client(offer.client().id()).buyer(offer.buyer().id()).upd(offer.updId()).offer(offer.offerId()).details(details)));
    }
}
