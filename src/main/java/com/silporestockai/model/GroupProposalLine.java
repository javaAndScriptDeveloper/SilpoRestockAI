package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One line of a group proposal as the group sees it and as consensus adds it to the cart (task 68).
 *
 * @param requestedName the plain name the model proposed
 * @param silpoProductId the catalog product the line resolved to, or null when the round was priced without a
 *     Silpo session
 * @param catalogName the catalog's own name for it, or the requested name when unresolved
 * @param quantity the quantity the cart call will carry — packages, or kilograms for a weighted product
 * @param unitPrice the catalog price per package (or per kilogram), or null when unknown
 * @param forWhom who the line is for, from the model
 */
public record GroupProposalLine(
        String requestedName,
        String silpoProductId,
        String catalogName,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        String forWhom) {

    /** What this line costs, or null when it has no price. */
    public BigDecimal lineCost() {
        if (unitPrice == null || quantity == null) {
            return null;
        }
        return unitPrice.multiply(quantity);
    }

    public boolean resolved() {
        return silpoProductId != null && !silpoProductId.isBlank();
    }
}
