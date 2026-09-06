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
 *     {@code CartBuildingService.sanityProblem}. Null for carts built before this field existed.
 * @param toppedUp lines added from the household's own baseline to lift a too-small cart over Silpo's minimum
 *     delivery order, named so the person can take them out — see {@code CartBuildingService.topUpFromBaseline}.
 *     Null for carts built before this field existed.
 * @param savings what Silpo's own promotions took off this cart ({@code calculation.subDiscount}); null when
 *     unknown, zero when nothing was on offer
 * @param goodsTotal what the goods alone come to ({@code calculation.productsTotal}), which is what Silpo measures
 *     its minimum order against; null for carts stored before this field existed
 * @param minimumOrder Silpo's minimum delivery order when the cart came back under it and so has no checkout link
 *     yet — the household decides what to do about that; null when the cart can check out
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
        List<String> skipped,
        List<String> toppedUp,
        BigDecimal savings,
        BigDecimal goodsTotal,
        BigDecimal minimumOrder) {

    /** The pre-minimum-order shape: a cart that could check out. */
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
            List<String> promotedProductIds,
            List<String> skipped,
            List<String> toppedUp,
            BigDecimal savings) {
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
                skipped,
                toppedUp,
                savings,
                null,
                null);
    }

    /** The pre-savings shape: nothing known about discounts. */
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
            List<String> promotedProductIds,
            List<String> skipped,
            List<String> toppedUp) {
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
                skipped,
                toppedUp,
                null);
    }

    /** The pre-top-up shape: nothing was added beyond what the list asked for. */
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
            List<String> promotedProductIds,
            List<String> skipped) {
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
                skipped,
                List.of());
    }

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

    /** Never null, whatever version of this record the stored JSON came from. */
    public List<String> toppedUpLines() {
        return toppedUp == null ? List.of() : toppedUp;
    }

    /** Whether any promotion actually took money off this cart. */
    public boolean hasSavings() {
        return savings != null && savings.signum() > 0;
    }

    /** Silpo refused this cart for its amount, so there is no checkout link until somebody adds to it. */
    public boolean belowMinimumOrder() {
        return minimumOrder != null && goodsTotal != null && goodsTotal.compareTo(minimumOrder) < 0;
    }

    /** How far the goods are from the minimum order; zero when the cart can check out. */
    public BigDecimal shortfall() {
        return belowMinimumOrder() ? minimumOrder.subtract(goodsTotal) : BigDecimal.ZERO;
    }
}
