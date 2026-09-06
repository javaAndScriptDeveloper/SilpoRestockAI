package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One line of the week's purchase list as the planner writes it: a product as it is named on a shelf, in a
 * quantity the shop actually sells.
 *
 * @param name the product as a person writes it on a list, in Ukrainian, no brand
 * @param quantity how much to buy for the whole household for the whole week
 * @param unit {@code кг}, {@code г}, {@code л}, {@code мл} or {@code шт}
 * @param category one of the fixed category labels the prompt gives, or null when the model left it out
 */
public record PurchaseLine(String name, BigDecimal quantity, String unit, String category) {}
