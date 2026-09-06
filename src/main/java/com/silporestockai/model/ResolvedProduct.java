package com.silporestockai.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A shopping list line matched to a real Silpo product, in the quantity the cart call will carry.
 *
 * @param requestedName the line as the household wrote it
 * @param productId Silpo's product id
 * @param quantity what {@code silpo_add_or_update_cart_products} is sent — kilograms for a weighted product, a
 *     count of packages otherwise
 * @param promotionId the partner placement this line came from (task 46), or null
 * @param catalogName the product's name in the catalog, for a message about this line; null when unknown
 * @param unitPrice the catalog price — per kilogram for a weighted product, per package otherwise; null when the
 *     search did not carry one
 * @param weighted whether Silpo sells it by weight
 */
public record ResolvedProduct(
        String requestedName,
        String productId,
        String companyId,
        String branchId,
        BigDecimal quantity,
        String unit,
        UUID promotionId,
        String catalogName,
        BigDecimal unitPrice,
        boolean weighted) {

    /** The pre-sanity-check shape: nothing known about the product beyond its id. */
    public ResolvedProduct(
            String requestedName,
            String productId,
            String companyId,
            String branchId,
            BigDecimal quantity,
            String unit,
            UUID promotionId) {
        this(requestedName, productId, companyId, branchId, quantity, unit, promotionId, null, null, false);
    }

    /** The pre-task-46 shape: an ordinary resolution, no partner behind it. */
    public ResolvedProduct(
            String requestedName,
            String productId,
            String companyId,
            String branchId,
            BigDecimal quantity,
            String unit) {
        this(requestedName, productId, companyId, branchId, quantity, unit, null);
    }

    public boolean promoted() {
        return promotionId != null;
    }
}
