package com.silporestockai.model;

/** What a scheduled one-off purchase builds when it fires. Persisted by name. */
public enum ScheduledAdHocTaskKind {
    /** A small cart of promoted snacks/treats around a theme (task 24/31). */
    SNACK_THEME,
    /** The ingredients of one named dish, resolved by catalog search (task 36). */
    DISH_INGREDIENTS
}
