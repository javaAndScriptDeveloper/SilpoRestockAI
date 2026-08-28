package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One line of a basket, as stored in the {@code items_json} column of {@code baseline_basket} and
 * {@code customer_order}.
 *
 * @param silpoProductId product id from the Silpo MCP catalogue; null for an item that could not be resolved
 * @param name human-readable name, which is what a check-in message will refer to
 * @param unit unit the quantity is counted in, e.g. {@code шт} or {@code кг}
 * @param quantity how much was ordered
 * @param price line price at the time of confirmation; prices move, so the snapshot keeps its own
 */
public record BasketItem(String silpoProductId, String name, String unit, BigDecimal quantity, BigDecimal price) {}
