package com.silporestockai.model;

/** Where an order is in its lifecycle. Persisted by name. */
public enum OrderStatus {
    /** Built but not yet shown to or accepted by the user. */
    DRAFT,
    /** The user pressed confirm. Payment still happens on Silpo's own checkout. */
    CONFIRMED,
    /** The user declined, or the order was abandoned. */
    CANCELLED
}
