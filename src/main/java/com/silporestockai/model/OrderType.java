package com.silporestockai.model;

/** Why an order exists. Persisted by name. */
public enum OrderType {
    /** The first basket, built at the end of onboarding. */
    INITIAL,
    /** A scheduled restock built from the delta against the baseline. */
    SCHEDULED_REORDER,
    /** A one-off request outside the normal cycle. */
    AD_HOC,
    /**
     * A package this household is sending to somebody else's address (task 81).
     *
     * <p>Never a baseline and never an inventory signal, for the same reason a blackout kit is neither: what a
     * friend was sent says nothing about how this household eats.
     */
    GIFT
}
