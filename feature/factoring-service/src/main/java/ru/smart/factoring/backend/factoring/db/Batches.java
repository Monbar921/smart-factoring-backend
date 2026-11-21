package ru.smart.factoring.backend.factoring.db;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.Arrays;

/**
 * Пакетная запись порциями (Р-88). Один пакет на сотни тысяч строк (объём сегмента) драйвер PostgreSQL отправляет
 * целиком, не читая ответов сервера: буферы сокета заполняются с обеих сторон, и прогон зависает - сервер простаивает,
 * поток прогона ждёт записи в сокет. Порция по {@value #CHUNK} строк держит буферы маленькими; с reWriteBatchedInserts
 * порция уходит несколькими многострочными INSERT.
 */
public final class Batches {
    public static final int CHUNK = 1000;

    private Batches() {
    }

    /**
     * Как {@link NamedParameterJdbcTemplate#batchUpdate(String, SqlParameterSource[])}, но порциями; результат -
     * счётчики всех порций подряд.
     */
    public static int[] update(final NamedParameterJdbcTemplate jdbc, final String sql, final SqlParameterSource[] batch) {
        if (batch.length <= CHUNK) {
            return jdbc.batchUpdate(sql, batch);
        }
        final int[] counts = new int[batch.length];
        for (int from = 0; from < batch.length; from += CHUNK) {
            final int to = Math.min(from + CHUNK, batch.length);
            final int[] chunk = jdbc.batchUpdate(sql, Arrays.copyOfRange(batch, from, to));
            System.arraycopy(chunk, 0, counts, from, Math.min(chunk.length, to - from));
        }
        return counts;
    }
}
