package com.silporestockai.utils;

import com.silporestockai.model.OfferedSlot;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * A delivery window as a person reads it: «пн, 7 вер · 09:00–10:30», in Kyiv time.
 *
 * <p>Silpo's slots carry no name of their own, only a start and an end in ISO form, and that raw string — with a
 * date, a T and a «+00:00» — was what the cart message and every «Інший час» button showed. The slot's
 * {@code label} stays the raw start on purpose: it doubles as the slot's identity and as the {@code timeslotStart}
 * the product search is scoped to. This is only what to print.
 */
public final class DeliverySlots {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final Locale UKRAINIAN = Locale.forLanguageTag("uk");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM", UKRAINIAN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", UKRAINIAN);

    private DeliverySlots() {}

    /** The window for a message or a button; the raw label when the start never parsed. */
    public static String describe(OfferedSlot slot) {
        if (slot == null) {
            return "час ще не обрано";
        }
        if (slot.startsAt() == null) {
            return slot.label();
        }
        ZonedDateTime start = slot.startsAt().atZone(KYIV);
        StringBuilder text = new StringBuilder(DAY.format(start).replace(".", ""))
                .append(" · ")
                .append(TIME.format(start));
        Instant end = parse(slot.end());
        if (end != null) {
            text.append('–').append(TIME.format(end.atZone(KYIV)));
        }
        return text.toString();
    }

    private static Instant parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
