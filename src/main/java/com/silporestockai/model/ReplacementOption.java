package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One substitute Silpo suggested for something it could not supply.
 *
 * @param productId what to add to the cart if the user accepts it
 * @param name what to show them
 * @param price its price, so "the same but 12 грн more" is visible before agreeing
 */
public record ReplacementOption(String productId, String name, BigDecimal price) {}
