package com.silporestockai.model;

/** Which meal of the day a planned dish belongs to. Persisted by name inside {@code meal_plan.plan_json}. */
public enum MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    /** Anything between the three — asked for only when the profile suggests it. */
    SNACK
}
