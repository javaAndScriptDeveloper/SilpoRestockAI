package com.silporestockai.model;

import java.time.Instant;

/**
 * One confirmed order a chat intent asked for: the request time and the confirmation time (task 75). A JPQL
 * projection — the gauge refresh reads these, never whole orders.
 */
public record IntentOrderDelay(String intent, Instant requestedAt, Instant confirmedAt) {}
