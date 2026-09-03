package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One ingredient of a planned meal.
 *
 * <p>{@code quantity} is a {@link BigDecimal} because task 08 turns these into a shopping list and then into a real
 * Silpo order — quantities that reach a shop must not have been through a {@code double}.
 *
 * @param name the ingredient as a person would write it on a list, in Ukrainian
 * @param quantity how much is needed for the meal
 * @param unit the unit the quantity is in, e.g. {@code кг}, {@code шт}, {@code л}
 * @param category a short category label from the fixed taxonomy the system prompt gives (e.g. "Молочні продукти"),
 *     or null when the model left it out — {@link com.silporestockai.service.CategoryKeywordFallbackService} fills
 *     that gap.
 */
public record PlannedIngredient(String name, BigDecimal quantity, String unit, String category) {}
