package com.silporestockai.model;

/**
 * Which multi-step conversation a chat is currently in. Persisted by name, so entries may be added but existing names
 * must not be renamed without a migration.
 */
public enum ConversationFlow {
    /** No flow in progress — the next message starts one. */
    NONE,
    /** First-run profile collection (task 06). */
    ONBOARDING,
    /** Periodic "what is left in the fridge" exchange (tasks 11 and 12). */
    CHECK_IN,
    /** Reviewing and confirming a proposed cart (task 10). */
    CART_CONFIRMATION,
    /** Reviewing a delta reorder: substitutes, delivery slot, confirm (task 15). */
    REORDER_CONFIRMATION
}
