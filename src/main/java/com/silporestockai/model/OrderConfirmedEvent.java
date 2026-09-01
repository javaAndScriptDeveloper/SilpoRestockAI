package com.silporestockai.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an order is stored as confirmed.
 *
 * <p>An event rather than a call so nothing in the ordering flow depends on optional integrations being configured,
 * connected or reachable. Today the only listener writes a calendar entry.
 *
 * @param userId whose order it is
 * @param orderId the stored {@code customer_order}
 * @param deliveryStartsAt when the delivery window opens, or null when the slot carried no readable time
 * @param slotLabel how the slot was shown to the user
 * @param itemCount how many lines the confirmed cart had
 */
public record OrderConfirmedEvent(
        UUID userId, UUID orderId, Instant deliveryStartsAt, String slotLabel, int itemCount) {}
