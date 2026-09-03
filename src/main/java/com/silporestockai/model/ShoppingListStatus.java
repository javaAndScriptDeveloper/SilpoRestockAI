package com.silporestockai.model;

/** Lifecycle of a {@code shopping_list_item} row. Persisted by name. */
public enum ShoppingListStatus {
    /** The list currently on screen. */
    ACTIVE,
    /** Placed as part of a confirmed order. */
    ORDERED,
    /** Superseded by a newer list; kept for history rather than deleted. */
    ARCHIVED
}
