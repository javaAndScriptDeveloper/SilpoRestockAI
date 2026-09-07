package com.silporestockai.model;

import java.util.List;

/**
 * One read of the household's Silpo order history (task 56).
 *
 * <p>Both facts come out of the same two tool calls, because they are different things to say to a person:
 * an empty list from a server that answered means «no orders in this account», an empty list from one that
 * did not means «could not check right now». Nothing else can tell them apart afterwards.
 *
 * @param orders recent orders, newest first — empty when the account has none or nothing answered
 * @param reachable whether at least one history tool answered without an error
 */
public record OrderHistory(List<PastOrderSummary> orders, boolean reachable) {

    public boolean isEmpty() {
        return orders == null || orders.isEmpty();
    }
}
