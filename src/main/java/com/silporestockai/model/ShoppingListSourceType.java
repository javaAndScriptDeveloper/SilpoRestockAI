package com.silporestockai.model;

/** Which generation path produced a {@code shopping_list_item} row. Persisted by name. */
public enum ShoppingListSourceType {
    /** Aggregated from a weekly plan's recipe ingredients. */
    RECIPE_DERIVED,
    /** The ready-to-eat product itself, for {@link CookingTimePreference#READY_MEALS_ONLY} households. */
    READY_MEAL_DIRECT
}
