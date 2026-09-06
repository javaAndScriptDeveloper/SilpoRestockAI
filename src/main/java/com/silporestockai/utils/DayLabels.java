package com.silporestockai.utils;

import java.time.DayOfWeek;
import lombok.experimental.UtilityClass;

/** The two-letter Ukrainian day labels a plan or a calendar line starts with: «Пн», «Вт», … «Нд». */
@UtilityClass
public class DayLabels {

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
