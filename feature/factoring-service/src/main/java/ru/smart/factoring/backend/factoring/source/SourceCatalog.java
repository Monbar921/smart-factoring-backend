package ru.smart.factoring.backend.factoring.source;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.smart.factoring.backend.api.common.ClientRefDto;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
                        select greatest((select max(upd_date) from demo_upd), (select max(payment_date) from demo_payment))""")
                .query(LocalDate.class)
                .optional();
    }

    /**
     * Дата снимка внешних данных по покупателям (ДД-6): из последнего импорта, иначе - по данным реестра.
     */
    public Optional<LocalDate> snapshotDate() {
        return jdbc.sql("""
                        select coalesce((select max(snapshot_date) from demo_import_batch), (select max(data_date) from demo_buyer))""")
                .query(LocalDate.class)
                .optional();
    }

    /**
     * Клиенты, которых загружает блок 1: с согласием на ЭДО и не клиенты факторинга (сноска к п. 1.1).
     */
    public List<ClientRefDto> eligibleClients() {
        return jdbc.sql("""
                        select inn, name from demo_client
                        where edo_consent and not factoring_client
                        order by name""")
                .query((rs, rowNum) -> new ClientRefDto(null, rs.getString("inn"), rs.getString("name")))
                .list();
    }
}
