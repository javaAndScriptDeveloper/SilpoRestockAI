package com.silporestockai.model;

/**
 * One meal in a {@link RecipeDay}: which meal of the day and what the dish is called. Deliberately no
 * ingredients — see {@link RecipeWeek}.
 *
 * @param type which meal of the day this is
 * @param name the dish, in Ukrainian, short
 */
public record RecipeMeal(MealType type, String name) {}
