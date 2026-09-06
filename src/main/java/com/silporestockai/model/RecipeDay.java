package com.silporestockai.model;

import java.time.DayOfWeek;
import java.util.List;

/**
 * One day of a {@link RecipeWeek}: the weekday and its meals by name.
 *
 * @param day the weekday the plan is aligned to
 * @param meals the dishes for that day, names only
 */
public record RecipeDay(DayOfWeek day, List<RecipeMeal> meals) {}
