package com.silporestockai.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a shopping-list line belongs to a promotion's category — the single definition of that question.
 *
 * <p>Task 46 asks it to decide which line a placement may answer; task 63 asks it again to count how many lines of
 * that category were resolved at all. Two copies of this rule would make every share wrong in a way no test could
 * see, because each half would still look right on its own.
 *
 * <p>Whole words only: a «молоко» placement must not claim «Молокопродукти».
 */
public final class CategoryWords {

    private CategoryWords() {}

    public static boolean matches(String lineName, String categoryOrQuery) {
        String line = normalise(lineName);
        String word = normalise(categoryOrQuery);
        if (line.isBlank() || word.isBlank()) {
            return false;
        }
        return Pattern.compile("(^|[^\\p{L}])" + Pattern.quote(word) + "(?=$|[^\\p{L}])")
                .matcher(line)
                .find();
    }

    public static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
