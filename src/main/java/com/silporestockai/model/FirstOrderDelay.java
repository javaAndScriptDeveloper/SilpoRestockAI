package com.silporestockai.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One household's account creation paired with its earliest confirmed order (task 54).
 *
 * <p>The raw material for «онбординг → перше замовлення», fetched as an aggregate projection so the gauge refresh does
 * not pull every user and every order into heap the way task 37's report does. The rule for turning the pair into a
 * duration lives in {@code MetricsService.onboardingToFirstOrder} — deliberately not here, so the report and the gauge
 * cannot drift apart.
 *
 * @param userId whose household this is
 * @param createdAt when the account was created
 * @param firstConfirmedAt when its first order was confirmed
 */
public record FirstOrderDelay(UUID userId, Instant createdAt, Instant firstConfirmedAt) {}
