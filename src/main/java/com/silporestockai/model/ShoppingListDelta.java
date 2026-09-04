package com.silporestockai.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * What changed between the shopping list a household had before an AI-triggered regeneration, and what it
 * has after (task 21). {@code unchangedCount} is a count, not a list: an unchanged line is worth
 * mentioning in a summary ("14 без змін"), never worth rendering itself.
 */
public record ShoppingListDelta(
        List<Line> added, List<Line> removed, List<QuantityChange> quantityChanged, int unchangedCount) {

    /** 1-2 total differences is small enough to show inline, without a full summary treatment. */
    public int totalChanges() {
        return added.size() + removed.size() + quantityChanged.size();
    }

    public boolean isTrivial() {
        return totalChanges() <= 2;
    }

    public record Line(String name, BigDecimal quantity, String unit) {}

    public record QuantityChange(String name, BigDecimal oldQuantity, BigDecimal newQuantity, String unit) {}
}
