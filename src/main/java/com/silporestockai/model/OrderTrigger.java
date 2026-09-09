package com.silporestockai.model;

import java.time.Instant;

/**
 * Why an order is being built, and when the person asked for it (task 75).
 *
 * <p>Passed explicitly from the intent router to the {@code present(...)} that writes the draft, so no service
 * holds it in a field. The draft stores it; confirmation reads it back and records intent→order speed. Orders
 * nobody asked for in a sentence — the weekly cart, the scheduled reorder cycle — carry none.
 *
 * @param intent the router's intent name, e.g. {@code HANGOVER_RELIEF}; read off a Grafana panel as a tag value
 * @param requestedAt when the request reached the app — before any model call, so the number is honest
 */
public record OrderTrigger(String intent, Instant requestedAt) {

    public static OrderTrigger of(String intent, Instant requestedAt) {
        return new OrderTrigger(intent, requestedAt);
    }
}
