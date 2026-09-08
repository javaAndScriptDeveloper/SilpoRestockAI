package com.silporestockai.model;

/**
 * One meal of the ready-meals answer.
 *
 * @param type breakfast, lunch, dinner or snack
 * @param candidate the 1-based position of the chosen product in the candidate list the model was shown; a number
 *     nobody offered is a defect the correction round names, never a product
 */
public record ReadyMealChoice(MealType type, Integer candidate) {}
