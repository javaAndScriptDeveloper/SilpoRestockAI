package com.silporestockai.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The cart as Silpo confirms it, plus what could not be put in it.
 *
 * <p>{@code bonusDecisionPending} is a question, not a decision: spending someone's loyalty points is not a default to
 * pick for them, so task 10 asks and this task only reports that there is something to ask about.
 *
 * @param cartId the Silpo cart
 * @param deliverySlot the slot the cart was validated against, which the confirmed order records
 * @param deliverySlotStartsAt when that slot begins, or null when the server's date format defeated parsing; a
 *     calendar event needs the instant, not the identifier
 * @param items what is in it now
 * @param total what it costs
 * @param validations warnings Silpo attached to the cart, e.g. an item that went out of stock
 * @param bonusAvailable loyalty bonuses that could be spent, zero when there are none
 * @param bonusDecisionPending true when bonuses are available, enabled and nobody has decided yet
 * @param checkoutWebLink where a person finishes the order in a browser
 * @param checkoutMobileLink the same in the Silpo app
 * @param unresolved names from the shopping list Silpo could not match to any product
 * @param promotedProductIds product ids that are in the cart because a partner promotion made them the match
 *     (task 46) — what the message marks with ★; empty when none
 */
public record CartSummary(
        String cartId,
        String deliverySlot,
        Instant deliverySlotStartsAt,
        List<BasketItem> items,
        BigDecimal total,
        List<String> validations,
        BigDecimal bonusAvailable,
        boolean bonusDecisionPending,
        String checkoutWebLink,
        String checkoutMobileLink,
        List<String> unresolved,
        List<String> promotedProductIds) {

    /** The pre-task-46 shape: no partner placements in this cart. */
    public CartSummary(
            String cartId,
            String deliverySlot,
            Instant deliverySlotStartsAt,
            List<BasketItem> items,
            BigDecimal total,
            List<String> validations,
            BigDecimal bonusAvailable,
            boolean bonusDecisionPending,
            String checkoutWebLink,
            String checkoutMobileLink,
            List<String> unresolved) {
        this(
                cartId,
                deliverySlot,
                deliverySlotStartsAt,
                items,
                total,
                validations,
                bonusAvailable,
                bonusDecisionPending,
                checkoutWebLink,
                checkoutMobileLink,
                unresolved,
                List.of());
    }

    public boolean isPromoted(String productId) {
        return productId != null && promotedProductIds != null && promotedProductIds.contains(productId);
    }
}
