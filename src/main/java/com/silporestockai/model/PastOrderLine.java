package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One line of a past Silpo order, as the order-history tools return it (task 35).
 *
 * @param productId Silpo's own product id — the whole reason this path needs no catalog search
 * @param name the product's name as Silpo has it
 * @param quantity how much was bought; 1 when the response did not say
 * @param unit Silpo's unit, or {@code шт} when the response did not say
 * @param price the line price paid, when present — seeds the list's price estimate (task 39)
 */
public record PastOrderLine(String productId, String name, BigDecimal quantity, String unit, BigDecimal price) {}
