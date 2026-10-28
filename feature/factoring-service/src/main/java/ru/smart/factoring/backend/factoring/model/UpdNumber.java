package ru.smart.factoring.backend.factoring.model;

import java.util.Locale;

/**
 * УПД в тексте: у номеров вида «U-1004» - «УПД U-1004», а номер «УПД-0255» (партии ML-команды) уже начинается со слова
 * «УПД», и второй раз его не добавляем (было «УПД УПД-0255»).
 */
public final class UpdNumber {
    private static final String PREFIX = "УПД";

    private UpdNumber() {
    }

    /**
     * «УПД U-1004» или «УПД-0255».
     */
    public static String label(final String number) {
        return hasPrefix(number) ? number : PREFIX + " " + number;
    }

    /**
     * «УПД № U-1004» или «УПД-0255» - для писем клиенту.
     */
    public static String labelWithNo(final String number) {
        return hasPrefix(number) ? number : PREFIX + " № " + number;
    }

    /**
     * Номер после слова «УПД» в строке «УПД: …»: «№ U-1004» или «УПД-0255».
     */
    public static String withNo(final String number) {
        return hasPrefix(number) ? number : "№ " + number;
    }

    private static boolean hasPrefix(final String number) {
        return number != null && number.toUpperCase(Locale.ROOT).startsWith(PREFIX);
    }
}
