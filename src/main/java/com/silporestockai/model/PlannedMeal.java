package com.silporestockai.model;

import java.util.List;

/**
 * One dish in a day's plan.
 *
 * @param type which meal of the day this is
 * @param name the dish, in Ukrainian
 * @param ingredients what it takes to cook it
 */
public record PlannedMeal(MealType type, String name, List<PlannedIngredient> ingredients) {}
