package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * A shopping list line that Silpo matched to a real product.
 *
 * @param requestedName the name the plan asked for, kept so an unmatched line can be named back to the user
 * @param productId Silpo's product identifier
 * @param companyId company the product belongs to
 * @param branchId branch the price and availability apply to
 * @param quantity how much to add
 * @param unit unit the quantity is counted in
 */
public record ResolvedProduct(
        String requestedName, String productId, String companyId, String branchId, BigDecimal quantity, String unit) {}
