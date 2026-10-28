package ru.smart.factoring.backend.factoring.run;

import ru.smart.factoring.backend.api.run.RunStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Значения в текстах журнала прогона - так, как их читает человек (замечания 28.10): дата 30.12.2025, сумма
 * «1 056 425,37 ₽», время «16,9 с», статус по-русски. В details журнала значения остаются машинными.
 */
public final class LogText {
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final long SECONDS_PER_MINUTE = 60;
    private static final long MILLIS_PER_SECOND = 1000;
    private static final Map<RunStatus, String> STATUSES = Map.of(
            RunStatus.RUNNING, "выполняется",
            RunStatus.DONE, "выполнен",
            RunStatus.DONE_WITH_WARNINGS, "выполнен с предупреждениями",
            RunStatus.STOPPED, "остановлен");

    private LogText() {
    }

    public static String date(final LocalDate date) {
        return date == null ? "—" : date.format(DATE);
    }

    /**
     * Сумма с разделителями разрядов (обычный пробел, не неразрывный) и копейками: «1 056 425,37 ₽».
     */
    public static String money(final BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        final DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(RU);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.00", symbols).format(amount.setScale(2, RoundingMode.HALF_UP)) + " ₽";
    }

    /**
     * «850 мс», «16,9 с», «2 мин 5 с».
     */
    public static String duration(final Duration duration) {
        final long millis = duration.toMillis();
        if (millis < MILLIS_PER_SECOND) {
            return millis + " мс";
        }
        final long seconds = duration.toSeconds();
        if (seconds < SECONDS_PER_MINUTE) {
            return String.format(RU, "%.1f с", millis / (double) MILLIS_PER_SECOND);
        }
        return (seconds / SECONDS_PER_MINUTE) + " мин " + (seconds % SECONDS_PER_MINUTE) + " с";
    }

    public static String status(final RunStatus status) {
        return STATUSES.getOrDefault(status, status.name());
    }
}
