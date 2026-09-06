package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * What a shopping list would roughly cost, from prices already in hand — never from a fresh Silpo call (task 39).
 *
 * <p>Honesty is the whole point: {@code unpricedCount} says how many lines had no price to add, so the message can say
 * «за 5 з 12 позицій» instead of presenting a partial sum as the total.
 *
 * @param total the sum of every line that could be priced, two decimals
 * @param pricedCount lines that contributed to {@code total}
 * @param unpricedCount lines with no price from any source
 */
public record PriceEstimate(BigDecimal total, int pricedCount, int unpricedCount) {

    public static PriceEstimate none() {
        return new PriceEstimate(BigDecimal.ZERO, 0, 0);
    }

    /** True when there is a number worth showing at all. */
    public boolean hasPrices() {
        return pricedCount > 0;
    }

    public boolean isPartial() {
        return unpricedCount > 0;
    }

    public int lineCount() {
        return pricedCount + unpricedCount;
    }
}
