package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One product Silpo's search returned for a shopping list line, in the terms the matcher needs to choose.
 *
 * @param name the shelf name, brand and all
 * @param price current price — per kilogram when {@code weighted}, per package otherwise
 * @param displayRatio Silpo's pack description («400г», «1л», «шт»)
 * @param weighted whether Silpo sells it by weight
 * @param stock how much the branch has
 * @param oldPrice the price before the current promotion, when there is one; null otherwise
 */
public record ProductCandidate(
        String name, BigDecimal price, String displayRatio, boolean weighted, BigDecimal stock, BigDecimal oldPrice) {

    /** The pre-promotion-aware shape: nothing known about a discount. */
    public ProductCandidate(String name, BigDecimal price, String displayRatio, boolean weighted, BigDecimal stock) {
        this(name, price, displayRatio, weighted, stock, null);
    }

    /** Whether the product is cheaper right now than its usual price. */
    public boolean discounted() {
        return oldPrice != null && price != null && oldPrice.compareTo(price) > 0;
    }
}
