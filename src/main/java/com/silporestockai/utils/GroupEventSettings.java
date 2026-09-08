package com.silporestockai.utils;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The organizer's optional settings for a group round (task 68), typed in one line: «бюджет 2000, привід: новий
 * рік, дата 31.12». Regex, not a model: three labelled fields with no ambiguity are not worth a network call.
 */
public final class GroupEventSettings {

    private static final Pattern BUDGET =
            Pattern.compile("бюджет\\s*:?\\s*(\\d[\\d\\s]{0,7})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("привід\\s*:?\\s*([^,;\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE =
            Pattern.compile("дата\\s*:?\\s*(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?", Pattern.CASE_INSENSITIVE);

    private GroupEventSettings() {}

    /** Whatever of the three fields the line names; empty when it names none. */
    public record Parsed(BigDecimal budget, String eventTag, LocalDate eventDate) {
        public boolean isEmpty() {
            return budget == null && eventTag == null && eventDate == null;
        }
    }

    public static Optional<Parsed> parse(String text, LocalDate today) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String line = text.toLowerCase(Locale.ROOT);
        BigDecimal budget = null;
        Matcher budgetMatch = BUDGET.matcher(line);
        if (budgetMatch.find()) {
            String digits = budgetMatch.group(1).replaceAll("\\s+", "");
            if (!digits.isEmpty()) {
                budget = new BigDecimal(digits);
            }
        }
        String tag = null;
        Matcher tagMatch = TAG.matcher(text);
        if (tagMatch.find()) {
            tag = tagMatch.group(1).strip();
            if (tag.isEmpty()) {
                tag = null;
            }
        }
        LocalDate date = null;
        Matcher dateMatch = DATE.matcher(line);
        if (dateMatch.find()) {
            date = toDate(dateMatch, today);
        }
        Parsed parsed = new Parsed(budget, tag, date);
        return parsed.isEmpty() ? Optional.empty() : Optional.of(parsed);
    }

    /** «31.12» is the next 31 December; a year, two- or four-digit, is taken as written. */
    private static LocalDate toDate(Matcher match, LocalDate today) {
        int day = Integer.parseInt(match.group(1));
        int month = Integer.parseInt(match.group(2));
        try {
            if (match.group(3) != null) {
                int year = Integer.parseInt(match.group(3));
                if (year < 100) {
                    year += 2000;
                }
                return LocalDate.of(year, month, day);
            }
            LocalDate candidate = LocalDate.of(today.getYear(), month, day);
            return candidate.isBefore(today) ? candidate.plusYears(1) : candidate;
        } catch (DateTimeException e) {
            return null;
        }
    }
}
