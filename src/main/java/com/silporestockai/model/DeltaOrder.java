package com.silporestockai.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A reorder as far as it can be built without asking anybody anything.
 *
 * <p>Three separate lists because task 15 renders them differently: what is in the cart, what needs a decision, and
 * what was deliberately left out. The cart carries no delivery slot yet — choosing one is task 15's job.
 *
 * @param userId whose reorder this is
 * @param type {@link OrderType#SCHEDULED_REORDER} when the cycle came round, {@link OrderType#AD_HOC} when one item
 *     could not wait
 * @param triggerItem the item that could not wait; null for a scheduled reorder
 * @param cart the verified Silpo cart, or null when there was nothing to order
 * @param context the cart, branch and company the order is bound to, so task 15 can keep talking to the same cart
 *     without asking Silpo which one it was again
 * @param reordered names that made it into the cart as asked
 * @param pendingReplacements names Silpo could not supply, with its suggestions
 * @param estimatedSavings rough difference between old and current prices on the promoted lines
 * @param excluded names left out because the household never touches them
 */
public record DeltaOrder(
        UUID userId,
        OrderType type,
        String triggerItem,
        CartSummary cart,
        CartContext context,
        List<String> reordered,
        List<ReplacementSuggestion> pendingReplacements,
        BigDecimal estimatedSavings,
        List<String> excluded) {

    /**
     * Nothing to buy this cycle. A normal outcome, not a failure.
     *
     * <p>Ignored by Jackson: this record makes a round trip through {@code conversation_state.context_json} between
     * two button taps, and an {@code isX()} method would otherwise be written out as a field the record cannot read
     * back.
     */
    @JsonIgnore
    public boolean isEmpty() {
        return reordered.isEmpty() && pendingReplacements.isEmpty();
    }
}
