package ru.smart.factoring.backend.factoring.load;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.smart.factoring.backend.factoring.run.RunContext;
import ru.smart.factoring.backend.factoring.run.RunLog;
import ru.smart.factoring.backend.factoring.run.RunStep;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Блок 1. Загрузка данных: из имитаций ЭДО, выписок и хранилища внешних данных (таблицы manual_upload_*) в основные таблицы.
 * <p>
 * Загрузка идемпотентна (НФР-3): повторная загрузка того же УПД (номер, дата, клиент) или платежа обновляет запись.
 * Документы и платежи берутся не позже даты расчёта (ПР-9): при первом прогоне - всё до даты расчёта, дальше -
 * в том числе новые с прошлого прогона (ПР-4). Справочники клиентов и реестр покупателей обновляются целиком.
 */
@Component
@RequiredArgsConstructor
public class LoadStep implements RunStep {
    private static final int STEP_NO = 1;
    /**
     * Клиенты, которых загружает блок 1: с согласием на ЭДО и не клиенты факторинга (сноска к п. 1.1).
     */
    private static final String ELIGIBLE = "dc.edo_consent and not dc.factoring_client";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final RunLog runLog;

    @Override
    public int stepNo() {
        return STEP_NO;
    }

    @Override
    public String name() {
        return "Загрузка данных";
    }

    @Override
    public Result execute(final RunContext context) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> load(context)));
    }

    private Result load(final RunContext context) {
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", context.getRunId())
                .addValue("calcDate", context.getCalcDate())
                .addValue("inns", context.getClientInns());
        final String scope = context.getClientInns() == null ? "" : " and dc.inn in (:inns)";

        loadReferences();
        final List<Long> clientIds = jdbc.queryForList(
                "select c.id from client c join manual_upload_client dc on dc.inn = c.inn where " + ELIGIBLE + scope + " order by c.id",
                params, Long.class);
        params.addValue("clientIds", clientIds.isEmpty() ? List.of(-1L) : clientIds);

        jdbc.update("""
                insert into contract (client_id, buyer_id, buyer_inn, number, contract_date, deferral_days)
                select c.id, b.id, d.buyer_inn, d.number, d.contract_date, d.deferral_days
                from manual_upload_contract d
                join client c on c.inn = d.client_inn
                left join buyer b on b.inn = d.buyer_inn
                where c.id in (:clientIds)
                on conflict (client_id, number) do update
                    set buyer_id = excluded.buyer_id, buyer_inn = excluded.buyer_inn,
                        contract_date = excluded.contract_date, deferral_days = excluded.deferral_days""", params);

        final List<Boolean> upds = jdbc.queryForList("""
                insert into upd (client_id, buyer_id, buyer_inn, contract_id, number, upd_date, amount, first_loaded_run_id)
                select c.id, b.id, d.buyer_inn, k.id, d.number, d.upd_date, d.amount, :runId
                from manual_upload_upd d
                join client c on c.inn = d.client_inn
                left join buyer b on b.inn = d.buyer_inn
                left join contract k on k.client_id = c.id and k.number = d.contract_number
                where c.id in (:clientIds) and d.upd_date <= :calcDate
                on conflict (number, upd_date, client_id) do update
                    set buyer_id = excluded.buyer_id, buyer_inn = excluded.buyer_inn, contract_id = excluded.contract_id,
                        amount = excluded.amount
                returning (xmax = 0)""", params, Boolean.class);

        final List<Boolean> payments = jdbc.queryForList("""
                insert into payment (source_id, client_id, payer_inn, amount, payment_date, purpose, first_loaded_run_id)
                select d.source_id, c.id, d.payer_inn, d.amount, d.payment_date, d.purpose, :runId
                from manual_upload_payment d
                join client c on c.inn = d.client_inn
                where c.id in (:clientIds) and d.payment_date <= :calcDate
                on conflict (source_id) do update
                    set client_id = excluded.client_id, payer_inn = excluded.payer_inn, amount = excluded.amount,
                        payment_date = excluded.payment_date, purpose = excluded.purpose
                returning (xmax = 0)""", params, Boolean.class);

        final Map<String, Object> buyers = jdbc.queryForMap("""
                select count(distinct u.buyer_inn) as buyers,
                       count(distinct u.buyer_inn) filter (where u.buyer_id is null) as without_data,
                       count(*) filter (where u.buyer_id is null) as upds_without_data
                from upd u
                where u.client_id in (:clientIds) and u.upd_date <= :calcDate""", params);
        final LocalDate snapshotDate = jdbc.queryForObject("""
                select coalesce((select max(snapshot_date) from manual_upload_batch), (select max(data_date) from buyer_external_data))""",
                params, LocalDate.class);

        logBuyersWithoutData(context, params);
        context.put(RunScope.class, new RunScope(clientIds, snapshotDate));
        context.getSummary().put("clientCount", clientIds.size());

        final Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("clients", clientIds.size());
        metrics.put("upds", upds.size());
        metrics.put("newUpds", upds.stream().filter(Boolean.TRUE::equals).count());
        metrics.put("payments", payments.size());
        metrics.put("newPayments", payments.stream().filter(Boolean.TRUE::equals).count());
        metrics.put("buyers", buyers.get("buyers"));
        metrics.put("buyersWithoutData", buyers.get("without_data"));
        metrics.put("updsWithoutBuyerData", buyers.get("upds_without_data"));
        // ЭП-3: дата данных прогона (выгрузки генератора) и, отдельно, дата снимка внешних данных ЕГРЮЛ/ЕФРСБ
        metrics.put("dataDate", Objects.toString(jdbc.queryForObject("select data_date from run where id = :runId",
                Map.of("runId", context.getRunId()), LocalDate.class), null));
        metrics.put("snapshotDate", snapshotDate != null ? snapshotDate.toString() : null);
        return Result.done(metrics);
    }

    /**
     * Менеджеры, клиенты и реестр покупателей с отчётностью и внешними данными - целиком: они нужны для лимита
     * покупателя по всем клиентам (п. 5.3) и для эмулятора CRM.
     */
    private void loadReferences() {
        final MapSqlParameterSource none = new MapSqlParameterSource();
        jdbc.update("""
                insert into manager (code, full_name)
                select code, full_name from manual_upload_manager
                on conflict (code) do update set full_name = excluded.full_name""", none);
        jdbc.update("""
                insert into client (inn, name, industry, okved_section, region, abs_liquidity, ar_turnover_days, is_core, manager_id,
                                    edo_consent, factoring_client)
                select dc.inn, dc.name, dc.industry, dc.okved_section, dc.region, dc.abs_liquidity, dc.ar_turnover_days, dc.is_core,
                       m.id, dc.edo_consent, dc.factoring_client
                from manual_upload_client dc
                join manager m on m.code = dc.manager_code
                where %s
                on conflict (inn) do update
                    set name = excluded.name, industry = excluded.industry, okved_section = excluded.okved_section,
                        region = excluded.region, abs_liquidity = excluded.abs_liquidity, ar_turnover_days = excluded.ar_turnover_days,
                        is_core = excluded.is_core, manager_id = excluded.manager_id, edo_consent = excluded.edo_consent,
                        factoring_client = excluded.factoring_client, loaded_at = now()""".formatted(ELIGIBLE), none);
        jdbc.update("""
                insert into buyer (inn, ogrn, name, okved, okved_section, region, registration_date, low_data_reason)
                select inn, ogrn, name, okved, okved_section, region, registration_date, low_data_reason from manual_upload_buyer
                on conflict (inn) do update
                    set ogrn = excluded.ogrn, name = excluded.name, okved = excluded.okved, okved_section = excluded.okved_section,
                        region = excluded.region, registration_date = excluded.registration_date,
                        low_data_reason = excluded.low_data_reason, loaded_at = now()""", none);
        jdbc.update("""
                insert into buyer_financials (buyer_id, year, simplified, assets_total, current_assets, receivables, cash, equity,
                    long_term_borrowings, short_term_liabilities, short_term_borrowings, payables, revenue, cost_of_sales,
                    sales_profit, interest_payable, net_profit, data_date)
                select b.id, f.year, f.simplified, f.assets_total, f.current_assets, f.receivables, f.cash, f.equity,
                    f.long_term_borrowings, f.short_term_liabilities, f.short_term_borrowings, f.payables, f.revenue, f.cost_of_sales,
                    f.sales_profit, f.interest_payable, f.net_profit, f.data_date
                from manual_upload_buyer_financials f
                join buyer b on b.inn = f.inn
                on conflict (buyer_id, year) do update
                    set simplified = excluded.simplified, assets_total = excluded.assets_total,
                        current_assets = excluded.current_assets, receivables = excluded.receivables, cash = excluded.cash,
                        equity = excluded.equity, long_term_borrowings = excluded.long_term_borrowings,
                        short_term_liabilities = excluded.short_term_liabilities,
                        short_term_borrowings = excluded.short_term_borrowings, payables = excluded.payables,
                        revenue = excluded.revenue, cost_of_sales = excluded.cost_of_sales, sales_profit = excluded.sales_profit,
                        interest_payable = excluded.interest_payable, net_profit = excluded.net_profit,
                        data_date = excluded.data_date""", none);
        // по каждому покупателю - худшее незакрытое дело о банкротстве, иначе последнее (data-model.md)
        jdbc.update("""
                insert into buyer_external_data (buyer_id, egrul_status, egrul_status_date, bankruptcy_case_number, bankruptcy_stage,
                                                 bankruptcy_start_date, bankruptcy_end_date, data_date)
                select b.id, s.status, s.status_date, k.case_number, k.stage, k.start_date, k.end_date,
                       coalesce(db.data_date, (select max(snapshot_date) from manual_upload_batch), current_date)
                from buyer b
                join manual_upload_buyer db on db.inn = b.inn
                left join manual_upload_egrul_status s on s.inn = b.inn
                left join lateral (
                    select * from manual_upload_bankruptcy_case bc
                    where bc.inn = b.inn
                    order by (bc.end_date is null) desc,
                             case bc.stage when 'Конкурсное производство' then 4 when 'Внешнее управление' then 3
                                           when 'Финансовое оздоровление' then 2 when 'Наблюдение' then 1 else 0 end desc,
                             bc.start_date desc nulls last
                    limit 1) k on true
                on conflict (buyer_id) do update
                    set egrul_status = excluded.egrul_status, egrul_status_date = excluded.egrul_status_date,
                        bankruptcy_case_number = excluded.bankruptcy_case_number, bankruptcy_stage = excluded.bankruptcy_stage,
                        bankruptcy_start_date = excluded.bankruptcy_start_date, bankruptcy_end_date = excluded.bankruptcy_end_date,
                        data_date = excluded.data_date, loaded_at = now()""", none);
        // готовый PD партии ML-команды (Р-54) - результат той же модели на ту же дату данных: блок 4 берёт его из кэша,
        // если версия модели сервиса совпадает с версией партии, иначе считает PD сам
        jdbc.update("""
                insert into buyer_pd_cache (buyer_id, data_date, model_version, pd, pd_source, low_data, reasons)
                select b.id, e.data_date, db.pd_model_version, db.pd, 'MODEL', false, '[]'::jsonb
                from manual_upload_buyer db
                join buyer b on b.inn = db.inn
                join buyer_external_data e on e.buyer_id = b.id
                where db.pd is not null and db.pd_model_version is not null
                on conflict (buyer_id, data_date, model_version) do update
                    set pd = excluded.pd, pd_source = excluded.pd_source, low_data = excluded.low_data, reasons = excluded.reasons
                    where buyer_pd_cache.pd is distinct from excluded.pd""", none);
    }

    /**
     * Покупатель без записи в хранилище внешних данных - штатный результат, не сбой (п. 1.4, НФР-4.2).
     */
    private void logBuyersWithoutData(final RunContext context, final MapSqlParameterSource params) {
        final List<RunLog.Entry> entries = jdbc.query("""
                        select u.buyer_inn, count(*) as upds, min(u.client_id) as client_id
                        from upd u
                        where u.client_id in (:clientIds) and u.upd_date <= :calcDate and u.buyer_id is null
                        group by u.buyer_inn
                        order by u.buyer_inn""", params,
                (rs, rowNum) -> RunLog.Entry.of(context.getRunId(), RunLog.Level.INFO, STEP_NO, "NO_BUYER_DATA",
                                "Нет данных по покупателю ИНН " + rs.getString("buyer_inn") + ": УПД не оцениваются ("
                                        + rs.getInt("upds") + " шт.)")
                        .details(Map.of("buyerInn", rs.getString("buyer_inn"))));
        runLog.write(entries);
    }
}
