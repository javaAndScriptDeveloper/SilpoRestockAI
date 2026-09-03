package com.silporestockai.model;

/**
 * How much cooking time a household has, collected by the onboarding WebApp form. {@link #READY_MEALS_ONLY} forks
 * {@code MealPlanService} onto a different system prompt entirely — see its Javadoc.
 */
public enum CookingTimePreference {
    /** Cooks a little every day. */
    COOKS_DAILY,
    /** Cooks once every few days, in advance. */
    COOKS_BATCH,
    /** Little to no time — wants ready-to-eat food only, no recipes. */
    READY_MEALS_ONLY
}
