package ru.smart.factoring.backend.factoring.risk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.smart.factoring.backend.factoring.db.Batches;
import ru.smart.factoring.backend.api.common.ClientRefDto;
import ru.smart.factoring.backend.api.risk.EfrsbEventKind;
import ru.smart.factoring.backend.api.risk.EfrsbEventRequest;
import ru.smart.factoring.backend.api.risk.RiskBuyerDto;
import ru.smart.factoring.backend.api.risk.RiskReactionDto;
import ru.smart.factoring.backend.factoring.error.ApiException;
import ru.smart.factoring.backend.factoring.json.Json;
import ru.smart.factoring.backend.factoring.model.UpdNumber;
import ru.smart.factoring.backend.factoring.run.LogText;
import ru.smart.factoring.backend.factoring.run.RunLog;
import ru.smart.factoring.backend.factoring.scoring.StopFactors;
import ru.smart.factoring.backend.factoring.source.SourceCatalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Реакция на негативное событие по покупателю между прогонами (Р-86, дорожная карта п. 1.3-10…11).
 * <p>
 * Ночной прогон увидит стоп-фактор только ночью, а предложение по покупателю в банкротстве менеджер может передать
 * в факторинг уже сегодня. Поэтому событие ЕФРСБ / ЕГРЮЛ обрабатывается сразу:
 * <ol>
 *     <li>событие пишется в источники (manual_upload_*): следующий прогон увидит тот же стоп-фактор (п. 4.1) и не
 *     создаст предложение заново, а отпечаток прогона изменится - ночной прогон запустится;</li>
 *     <li>стоп-фактор - по тем же правилам, что в блоке 4 ({@link StopFactors}), по худшему незакрытому делу;</li>
 *     <li>предложения покупателя в очереди менеджеров отзываются (REVOKED, причина STOP_FACTOR);</li>
 *     <li>сигнал в риск-подразделение (imitation_risk_signal, источник EFRSB) - и по уже переданным в факторинг;</li>
 *     <li>уведомления менеджерам: «предложение отозвано» по каждому предложению и «сигнал в риск» по покупателю;</li>
 *     <li>всё - в журнал вне прогона (run_id null), как решения менеджера.</li>
 * </ol>
 * Событие, которое не стоп-фактор (намерение кредитора), только записывается: предложения остаются.
 * <p>
 * На пилоте событие приходит из подписки на мониторинг контрагентов (Федресурс, сервис проверки контрагентов банка);
 * в демо его присылает оператор формой или файлом events.csv - после импорта проверяются все покупатели с действующими
 * предложениями ({@link #recheckActiveBuyers()}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskEventService {
    public static final String SOURCE = "EFRSB";
    /** номер нового дела в демо: А40-NNNNNN/ГГГГ, шесть цифр от ИНН */
    private static final int CASE_NUMBER_FROM = 100_000;
    private static final int CASE_NUMBER_RANGE = 900_000;
    private static final Pattern INN = Pattern.compile("\\d{10}|\\d{12}");
    private static final Map<String, String> STOP_FACTORS = Map.of(
            "BANKRUPTCY", "банкротство", "LIQUIDATION", "ликвидация", "EXCLUSION", "исключение из ЕГРЮЛ");
    /**
     * Худшее незакрытое дело покупателя, иначе последнее - как при загрузке в buyer_external_data (LoadStep).
     */
    private static final String WORST_CASE = """
            select bc.case_number, bc.stage, bc.end_date from manual_upload_bankruptcy_case bc
            where bc.inn = :inn
            order by (bc.end_date is null) desc,
                     case bc.stage when 'Конкурсное производство' then 4 when 'Внешнее управление' then 3
                                   when 'Финансовое оздоровление' then 2 when 'Наблюдение' then 1 else 0 end desc,
                     bc.start_date desc nulls last
            limit 1""";

    private final NamedParameterJdbcTemplate jdbc;
    private final SourceCatalog sourceCatalog;
    private final RunLog runLog;
    private final Json json;

    /**
     * Покупатели с предложениями в очереди или переданными в факторинг: им есть что отзывать.
     */
    public List<RiskBuyerDto> activeBuyers() {
        return jdbc.query("""
                        select b.inn, b.name,
                               count(*) filter (where o.status = 'QUEUED') as queued,
                               coalesce(sum(o.ead) filter (where o.status = 'QUEUED'), 0) as queued_ead,
                               count(*) filter (where o.status = 'TRANSFERRED') as transferred
                        from offer o join buyer b on b.id = o.buyer_id
                        where o.status in ('QUEUED', 'TRANSFERRED')
                        group by b.inn, b.name
                        order by queued_ead desc, b.name""", new MapSqlParameterSource(),
                (rs, rowNum) -> new RiskBuyerDto(rs.getString("inn"), rs.getString("name"), rs.getInt("queued"),
                        rs.getBigDecimal("queued_ead"), rs.getInt("transferred")));
    }

    /**
     * Новое событие ЕФРСБ по покупателю: запись в источники и немедленная реакция.
     */
    @Transactional
    public RiskReactionDto receive(final EfrsbEventRequest request) {
        if (request == null || request.kind() == null) {
            throw ApiException.validation("Укажите событие: INTENT, OBSERVATION, BANKRUPT или LIQUIDATION");
        }
        final String inn = request.buyerInn() == null ? "" : request.buyerInn().trim();
        if (!INN.matcher(inn).matches()) {
            throw ApiException.validation("ИНН покупателя - 10 или 12 цифр: " + request.buyerInn());
        }
        final Buyer buyer = buyer(inn);
        final LocalDate eventDate = request.eventDate() != null ? request.eventDate()
                : sourceCatalog.dataDate().orElseGet(LocalDate::now);
        final String event = write(inn, request.kind(), eventDate, request.caseNumber());
        log.info("Событие ЕФРСБ по покупателю {}: {}", inn, event);
        return react(buyer, event + " от " + LogText.date(eventDate), eventDate);
    }

    /**
     * После импорта events.csv или выгрузки: покупатели с действующими предложениями, у которых появился стоп-фактор.
     */
    @Transactional
    public List<RiskReactionDto> recheckActiveBuyers() {
        final List<RiskReactionDto> reactions = new ArrayList<>();
        for (final RiskBuyerDto active : activeBuyers()) {
            final Buyer buyer = buyer(active.inn());
            if (stopFactor(active.inn()) != null) {
                reactions.add(react(buyer, "данные ЕФРСБ / ЕГРЮЛ из загрузки", sourceCatalog.snapshotDate().orElseGet(LocalDate::now)));
            }
        }
        return reactions;
    }

    private Buyer buyer(final String inn) {
        return jdbc.query("select id, inn, name from buyer where inn = :inn", new MapSqlParameterSource("inn", inn),
                        (rs, rowNum) -> new Buyer(rs.getLong("id"), rs.getString("inn"), rs.getString("name")))
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Покупатель с ИНН", inn));
    }

    /**
     * Событие - в источники ЕФРСБ / ЕГРЮЛ ручной загрузки, как если бы оно пришло файлом events.csv.
     */
    private String write(final String inn, final EfrsbEventKind kind, final LocalDate eventDate, final String requestedCase) {
        final String caseNumber = caseNumber(inn, requestedCase, eventDate);
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("inn", inn)
                .addValue("caseNumber", caseNumber)
                .addValue("date", eventDate)
                .addValue("message", "SIM-" + System.currentTimeMillis());
        final String eventType;
        final String event;
        switch (kind) {
            case INTENT -> {
                eventType = "Сообщение о намерении";
                event = "Намерение кредитора обратиться в суд с заявлением о банкротстве";
                params.addValue("caseNumber", null);
            }
            case OBSERVATION -> {
                eventType = "Судебный акт";
                event = "Введение наблюдения";
                upsertCase(params, "Наблюдение", "observation_from = :date,");
            }
            case BANKRUPT -> {
                eventType = "Судебный акт";
                event = "Признание должника банкротом и открытие конкурсного производства";
                upsertCase(params, "Конкурсное производство", "declared_bankrupt_on = :date, receivership_from = :date,");
            }
            case LIQUIDATION -> {
                eventType = "ЕГРЮЛ";
                event = "Принято решение о ликвидации";
                params.addValue("caseNumber", null);
                jdbc.update("""
                        insert into manual_upload_egrul_status (inn, status, status_date)
                        values (:inn, 'Находится в стадии ликвидации', :date)
                        on conflict (inn) do update set status = excluded.status, status_date = excluded.status_date""", params);
            }
            default -> throw ApiException.validation("Неизвестное событие: " + kind);
        }
        jdbc.update("""
                        insert into manual_upload_efrsb_event (inn, case_number, event_type, event, event_date, published_on, message_number,
                                                               annulled, url)
                        values (:inn, :caseNumber, :eventType, :event, :date, :date, :message, false, null)""",
                params.addValue("eventType", eventType).addValue("event", event));
        return event;
    }

    private void upsertCase(final MapSqlParameterSource params, final String stage, final String dates) {
        jdbc.update("""
                insert into manual_upload_bankruptcy_case (inn, case_number, stage, start_date)
                values (:inn, :caseNumber, :stage, :date)
                on conflict (inn, case_number) do update set stage = excluded.stage, end_date = null, outcome = null,
                    start_date = coalesce(manual_upload_bankruptcy_case.start_date, excluded.start_date)""",
                params.addValue("stage", stage));
        jdbc.update("update manual_upload_bankruptcy_case set " + dates + " stage = :stage where inn = :inn and case_number = :caseNumber",
                params);
    }

    /**
     * Номер дела: заданный, иначе незакрытое дело покупателя, иначе новый в формате арбитражного суда.
     */
    private String caseNumber(final String inn, final String requested, final LocalDate eventDate) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return jdbc.query("""
                        select case_number from manual_upload_bankruptcy_case where inn = :inn and end_date is null
                        order by start_date desc nulls last limit 1""",
                        new MapSqlParameterSource("inn", inn), (rs, rowNum) -> rs.getString(1))
                .stream().findFirst()
                .orElse("А40-" + (CASE_NUMBER_FROM + Math.floorMod(inn.hashCode(), CASE_NUMBER_RANGE)) + "/" + eventDate.getYear());
    }

    private StopFactors.StopFactor stopFactor(final String inn) {
        final MapSqlParameterSource params = new MapSqlParameterSource("inn", inn);
        // статус и стадия бывают пустыми: списки, а не findFirst - он не принимает null
        final List<String> statuses = jdbc.query("select status from manual_upload_egrul_status where inn = :inn", params,
                (rs, rowNum) -> rs.getString(1));
        final String egrul = statuses.isEmpty() ? null : statuses.getFirst();
        final List<String[]> cases = jdbc.query(WORST_CASE, params, (rs, rowNum) -> new String[]{rs.getString("stage"),
                rs.getString("end_date")});
        if (cases.isEmpty()) {
            return StopFactors.classify(egrul, null, null);
        }
        final String endDate = cases.getFirst()[1];
        return StopFactors.classify(egrul, cases.getFirst()[0], endDate == null ? null : LocalDate.parse(endDate));
    }

    private RiskReactionDto react(final Buyer buyer, final String event, final LocalDate eventDate) {
        final StopFactors.StopFactor stop = stopFactor(buyer.inn());
        final ClientRefDto ref = new ClientRefDto(buyer.id(), buyer.inn(), buyer.name());
        final List<RunLog.Entry> entries = new ArrayList<>();
        if (stop == null) {
            entries.add(RunLog.Entry.of(null, RunLog.Level.INFO, null, "EFRSB_EVENT", "Событие по покупателю " + buyer.title()
                    + ": " + event + ". Стоп-фактора нет, предложения остаются").buyer(buyer.id()));
            runLog.write(entries);
            return new RiskReactionDto(ref, event, null, 0, BigDecimal.ZERO, 0, 0, false,
                    "Стоп-фактора нет: предложения по покупателю остаются, событие учтёт следующий анализ");
        }
        final String stopName = STOP_FACTORS.get(stop.name());
        entries.add(RunLog.Entry.of(null, RunLog.Level.WARN, null, "EFRSB_EVENT", "Событие по покупателю " + buyer.title() + ": "
                + event + ". Стоп-фактор: " + stopName).buyer(buyer.id()));

        final List<ActiveOffer> active = jdbc.query("""
                        select o.id, o.status, o.upd_id, o.client_id, o.manager_id, o.ead, u.number
                        from offer o join upd u on u.id = o.upd_id
                        where o.buyer_id = :buyerId and o.status in ('QUEUED', 'TRANSFERRED')
                        order by o.id""", new MapSqlParameterSource("buyerId", buyer.id()),
                (rs, rowNum) -> new ActiveOffer(rs.getLong("id"), rs.getString("status"), rs.getLong("upd_id"), rs.getLong("client_id"),
                        rs.getLong("manager_id"), rs.getBigDecimal("ead"), rs.getString("number")));
        final List<ActiveOffer> queued = active.stream().filter(offer -> "QUEUED".equals(offer.status())).toList();
        final List<ActiveOffer> transferred = active.stream().filter(offer -> "TRANSFERRED".equals(offer.status())).toList();
        final BigDecimal revokedEad = queued.stream().map(ActiveOffer::ead).reduce(BigDecimal.ZERO, BigDecimal::add);

        final List<MapSqlParameterSource> notifications = new ArrayList<>();
        for (final ActiveOffer offer : queued) {
            jdbc.update("update offer set status = 'REVOKED', revoke_reason = 'STOP_FACTOR', updated_at = now() where id = :id and status = 'QUEUED'",
                    new MapSqlParameterSource("id", offer.id()));
            final Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("updNumber", offer.updNumber());
            payload.put("ead", offer.ead());
            payload.put("stopFactor", stop.name());
            payload.put("event", event);
            payload.put("source", SOURCE);
            notifications.add(notification("OFFER_REVOKED", offer.managerId(), offer.clientId(), buyer.id(), offer.updId(), offer.id(), payload));
            entries.add(RunLog.Entry.of(null, RunLog.Level.INFO, null, "OFFER_REVOKED", "Предложение " + offer.id() + " по "
                            + UpdNumber.label(offer.updNumber()) + " отозвано: " + stopName + " покупателя (" + event + ")")
                    .client(offer.clientId()).buyer(buyer.id()).upd(offer.updId()).offer(offer.id()));
        }

        final boolean signal = !active.isEmpty() && (!queued.isEmpty() || !signalled(buyer.inn(), stop));
        if (signal) {
            final Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", SOURCE);
            payload.put("buyerInn", buyer.inn());
            payload.put("stopFactor", stop.name());
            payload.put("event", event);
            payload.put("eventDate", eventDate);
            payload.put("revokedOffers", queued.size());
            payload.put("revokedEad", revokedEad);
            payload.put("transferredOffers", transferred.size());
            payload.put("transferredEad", transferred.stream().map(ActiveOffer::ead).reduce(BigDecimal.ZERO, BigDecimal::add));
            payload.put("updNumbers", active.stream().map(ActiveOffer::updNumber).toList());
            jdbc.update("""
                            insert into imitation_risk_signal (run_id, buyer_inn, upd_ids, payload, source)
                            values (null, :buyerInn, cast(:updIds as jsonb), cast(:payload as jsonb), :source)""",
                    new MapSqlParameterSource()
                            .addValue("buyerInn", buyer.inn())
                            .addValue("updIds", json.write(active.stream().map(ActiveOffer::updId).toList()))
                            .addValue("payload", json.write(payload))
                            .addValue("source", SOURCE));
            active.stream().map(ActiveOffer::managerId).distinct().forEach(managerId -> {
                final ActiveOffer first = active.stream().filter(offer -> Objects.equals(offer.managerId(), managerId)).findFirst().orElseThrow();
                notifications.add(notification("RISK_SIGNAL", managerId, first.clientId(), buyer.id(), null, null, payload));
            });
            entries.add(RunLog.Entry.of(null, RunLog.Level.INFO, null, "RISK_SIGNAL", "Сигнал в риск-подразделение: покупатель "
                    + buyer.title() + ", " + stopName + "; отозвано предложений " + queued.size() + " на " + LogText.money(revokedEad)
                    + ", передано в факторинг ранее " + transferred.size()).buyer(buyer.id()));
        }
        Batches.update(jdbc, """
                        insert into notification (run_id, manager_id, type, client_id, buyer_id, upd_id, offer_id, payload)
                        values (null, :managerId, :type, :clientId, :buyerId, :updId, :offerId, cast(:payload as jsonb))""",
                notifications.toArray(MapSqlParameterSource[]::new));
        runLog.write(entries);
        final int managers = (int) notifications.stream().map(params -> params.getValue("managerId")).distinct().count();
        log.warn("Покупатель {}: {} - отозвано предложений {}, сигнал в риск {}", buyer.inn(), stopName, queued.size(), signal);
        return new RiskReactionDto(ref, event, stop.name(), queued.size(), revokedEad, transferred.size(), managers, signal,
                message(stopName, queued.size(), revokedEad, transferred.size(), managers, signal));
    }

    /**
     * Сигнал о том же стоп-факторе покупателя уже отправлен: повторное событие без новых отзывов сигнал не дублирует.
     */
    private boolean signalled(final String inn, final StopFactors.StopFactor stop) {
        final Integer count = jdbc.queryForObject("""
                        select count(*) from imitation_risk_signal
                        where source = :source and buyer_inn = :inn and payload ->> 'stopFactor' = :stop""",
                new MapSqlParameterSource().addValue("source", SOURCE).addValue("inn", inn).addValue("stop", stop.name()),
                Integer.class);
        return count != null && count > 0;
    }

    private static String message(final String stopName, final int revoked, final BigDecimal ead, final int transferred,
                                  final int managers, final boolean signal) {
        final StringBuilder text = new StringBuilder("Стоп-фактор: ").append(stopName).append(". ");
        text.append(revoked > 0 ? "Отозвано предложений: " + revoked + " на " + LogText.money(ead) : "Предложений в очереди не было");
        if (transferred > 0) {
            text.append("; переданных в факторинг: ").append(transferred);
        }
        text.append(signal ? ". Сигнал в риск-подразделение отправлен" : ". Сигнал в риск уже был");
        if (managers > 0) {
            text.append(", уведомлено менеджеров: ").append(managers);
        }
        return text.toString();
    }

    private MapSqlParameterSource notification(final String type, final long managerId, final Long clientId, final long buyerId,
                                                      final Long updId, final Long offerId, final Map<String, Object> payload) {
        return new MapSqlParameterSource()
                .addValue("type", type)
                .addValue("managerId", managerId)
                .addValue("clientId", clientId)
                .addValue("buyerId", buyerId)
                .addValue("updId", updId)
                .addValue("offerId", offerId)
                .addValue("payload", json.write(payload));
    }

    private record Buyer(long id, String inn, String name) {
        String title() {
            return name != null && !name.isBlank() ? name + " (ИНН " + inn + ")" : "ИНН " + inn;
        }
    }

    private record ActiveOffer(long id, String status, long updId, long clientId, long managerId, BigDecimal ead, String updNumber) {
    }
}
