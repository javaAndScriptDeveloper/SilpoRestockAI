package com.silporestockai.model;

/**
 * How much of the confirmation ceremony a user still needs.
 *
 * <p>An auto-confirm tier is deliberately absent: the product brief says to leave room for it, not to build it. Adding
 * it is a product decision, not a schema one.
 */
public enum TrustTier {
    /** Every order is reviewed item by item before confirmation. */
    MANUAL_CONFIRM,
    /** The user has stopped editing suggestions; show a summary and a single confirm. */
    FAST_CONFIRM
}
