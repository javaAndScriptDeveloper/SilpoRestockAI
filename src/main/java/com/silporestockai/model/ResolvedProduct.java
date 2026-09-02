package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * A shopping list line that Silpo matched to a real product.
 *
 * @param requestedName the name the plan asked for, kept so an unmatched line can be named back to the user
 * @param productId Silpo's product identifier
 * @param companyId company the product belongs to
 * @param branchId branch the price and availability apply to
 * @param quantity how many of Silpo's own {@code displayRatio}-sized units to add — not the household's own grams or
 *     millilitres; {@code CartBuildingService.cartQuantity} converts one into the other
 * @param unit the household's own unit, kept for display only — never Silpo's own unit of sale
 */
public record ResolvedProduct(
        String requestedName, String productId, String companyId, String branchId, BigDecimal quantity, String unit) {}
