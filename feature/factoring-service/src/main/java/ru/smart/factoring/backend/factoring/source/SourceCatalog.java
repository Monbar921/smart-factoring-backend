package ru.smart.factoring.backend.factoring.source;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.api.common.ClientRefDto;
import ru.smart.factoring.backend.api.run.RunBatchDto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Что есть в источниках (имитациях ЭДО, выписок и хранилища внешних данных) до загрузки: для формы запуска (ЭП-1).
 */
@Component
@RequiredArgsConstructor
public class SourceCatalog {
    private final JdbcClient jdbc;

    /**
     * Последняя дата данных: самая поздняя дата УПД или платежа (ПР-9, ДД-8).
     */
    public Optional<LocalDate> dataDate() {
        return jdbc.sql("""
                        select greatest((select max(upd_date) from manual_upload_upd), (select max(payment_date) from manual_upload_payment))""")
                .query(LocalDate.class)
                .optional();
    }

    /**
     * Дата снимка внешних данных по покупателям (ДД-6): из последнего импорта, иначе - по данным реестра.
     */
    public Optional<LocalDate> snapshotDate() {
        return jdbc.sql("""
                        select coalesce((select max(snapshot_date) from manual_upload_batch), (select max(data_date) from manual_upload_buyer))""")
                .query(LocalDate.class)
                .optional();
    }

    /**
     * Партии ручной загрузки и их клиенты, которых загружает блок 1 (Р-62), - по номеру партии.
     */
    public List<RunBatchDto> batches() {
        final Map<String, List<String>> byBatch = new TreeMap<>();
        jdbc.sql("""
                        select batch_id, inn from manual_upload_client
                        where batch_id is not null and edo_consent and not factoring_client
                        order by batch_id, name""")
                .query((RowCallbackHandler) rs -> byBatch.computeIfAbsent(rs.getString("batch_id"), id -> new ArrayList<>())
                        .add(rs.getString("inn")));
        return byBatch.entrySet().stream().map(entry -> new RunBatchDto(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * Клиенты, которых загружает блок 1: с согласием на ЭДО и не клиенты факторинга (сноска к п. 1.1).
     */
    public List<ClientRefDto> eligibleClients() {
        return jdbc.sql("""
                        select inn, name from manual_upload_client
                        where edo_consent and not factoring_client
                        order by name""")
                .query((rs, rowNum) -> new ClientRefDto(null, rs.getString("inn"), rs.getString("name")))
                .list();
    }
}
