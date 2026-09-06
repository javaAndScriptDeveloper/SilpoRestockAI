package com.silporestockai.model;

import java.util.List;

/**
 * Claude's structured answer to "what do I buy to cook this" (task 36): one dish, one shopping list.
 *
 * @param dishName the dish as the model understood it — echoed back so a misread is visible
 * @param servings how many portions the quantities are for
 * @param items generic ingredient lines («спагеті», «панчета», «яйця»), the shape {@code CartBuildingService}
 *     resolves by name search — no product ids here, ever
 */
public record DishIngredients(String dishName, Integer servings, List<PlannedIngredient> items) {}
