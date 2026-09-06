package com.silporestockai.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The verified cart as read back from Silpo after everything was added, plus the household-facing facts around it.
 *
 * @param unresolved requested names Silpo had no acceptable product for
 * @param promotedProductIds product ids a partner placement put in the cart (task 46)
 * @param skipped requested lines whose resolved product was found but deliberately not added, each with the reason
 *     in plain words — a line that would have cost a small fortune or come as a crate. See
 *     {@code CartBuildingService.sanityCheck}. Null for carts built before this field existed.
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
        List<String> promotedProductIds,
        List<String> skipped) {

    /** The pre-sanity-check shape: nothing was held back. */
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
            List<String> unresolved,
            List<String> promotedProductIds) {
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
                promotedProductIds,
                List.of());
    }

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
                List.of(),
                List.of());
    }

    public boolean isPromoted(String productId) {
        return productId != null && promotedProductIds != null && promotedProductIds.contains(productId);
    }

    /** Never null, whatever version of this record the stored JSON came from. */
    public List<String> skippedLines() {
        return skipped == null ? List.of() : skipped;
    }
}
