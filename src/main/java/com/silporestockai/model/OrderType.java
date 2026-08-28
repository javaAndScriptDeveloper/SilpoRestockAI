package com.silporestockai.model;

/** Why an order exists. Persisted by name. */
public enum OrderType {
    /** The first basket, built at the end of onboarding. */
    INITIAL,
    /** A scheduled restock built from the delta against the baseline. */
    SCHEDULED_REORDER,
    /** A one-off request outside the normal cycle. */
    AD_HOC
}
