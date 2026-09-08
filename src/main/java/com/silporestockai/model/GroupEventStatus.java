package com.silporestockai.model;

/**
 * Where a group drinks round is (task 68). Persisted by name.
 *
 * <p>The round waits in {@link #COLLECTING_REPLIES} for as long as it takes — there is no timeout by product
 * decision. Only the organizer's tap moves it on, and that tap is the moment the vote's denominator is fixed.
 */
public enum GroupEventStatus {
    /** Replies are being collected; nobody is counted yet. */
    COLLECTING_REPLIES,
    /** The set of voters is frozen and a proposal is on the table (or being regenerated after a revision). */
    PROPOSED,
    /** Every counted participant approved the current proposal; the lines went to the organizer's cart. */
    APPROVED,
    /** The organizer confirmed the order in their private chat. */
    ORDERED,
    /** Superseded by a new round in the same chat, or the bot was removed. */
    CANCELLED
}
