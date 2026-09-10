package com.silporestockai.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
 * @param deliverySlotRepicked whether this cart is on a window picked during the build because the one booked on
 *     it had gone (task 76) — the message says so, since the household chose the earlier one
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
        BigDecimal minimumOrder,
        boolean deliverySlotRepicked) {

    /** The pre-task-76 shape: the window this cart was built for is the one it was booked on. */
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
            BigDecimal savings,
            BigDecimal goodsTotal,
            BigDecimal minimumOrder) {
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
                goodsTotal,
                minimumOrder,
                false);
    }

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

    /**
     * The same cart, with each line paired to the shopping list line it was bought for where that is known (task
     * 39). Silpo's cart read-back carries only its own catalog names, and the pairing is what lets the baseline
     * this cart becomes price a later list without asking Silpo anything. A line already carrying a name keeps it.
     */
    public CartSummary withRequestedNames(Map<String, String> requestedNameByProductId) {
        if (requestedNameByProductId == null || requestedNameByProductId.isEmpty() || items == null) {
            return this;
        }
        List<BasketItem> named = items.stream()
                .map(line -> line.requestedName() != null || line.silpoProductId() == null
                        ? line
                        : new BasketItem(
                                line.silpoProductId(),
                                line.name(),
                                line.unit(),
                                line.quantity(),
                                line.price(),
                                requestedNameByProductId.get(line.silpoProductId())))
                .toList();
        return new CartSummary(
                cartId,
                deliverySlot,
                deliverySlotStartsAt,
                named,
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
                goodsTotal,
                minimumOrder,
                deliverySlotRepicked);
    }

    /**
     * Whether a partner placement put this product in the cart (task 46). Internal only: since task 62 no message a
     * household reads distinguishes a promoted line, and the funnel is counted from the promotion's own events, not
     * from here.
     */
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
