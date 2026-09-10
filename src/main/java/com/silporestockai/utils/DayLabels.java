package com.silporestockai.utils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/** The two-letter Ukrainian day labels a plan or a calendar line starts with: «Пн», «Вт», … «Нд». */
@UtilityClass
public class DayLabels {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final DateTimeFormatter DAY_AND_MONTH =
            DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("uk"));

    /**
     * «17 вересня», in Kyiv time — the way a deadline is said out loud when a mode ends on one (task 67).
     *
     * @return the date, or «кінця тижня» when there is no instant to name
     */
    public static String dayAndMonth(Instant instant) {
        return instant == null ? "кінця тижня" : DAY_AND_MONTH.format(instant.atZone(KYIV));
    }

    public static String shortLabel(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "Пн";
            case TUESDAY -> "Вт";
            case WEDNESDAY -> "Ср";
            case THURSDAY -> "Чт";
            case FRIDAY -> "Пт";
            case SATURDAY -> "Сб";
            case SUNDAY -> "Нд";
        };
    }
}
