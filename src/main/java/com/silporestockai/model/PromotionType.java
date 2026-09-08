package com.silporestockai.model;

/**
 * Why a placement exists (task 63). The resolution mechanism is identical for both; the reporting is not — external
 * revenue and internal margin are two different pools and are never added together into one number.
 */
public enum PromotionType {
    /** An outside brand pays Silpo to be the preferred answer for a category. */
    PAID_PARTNER,
    /** Silpo prefers its own private-label or high-margin brand, with no external payer. */
    OWN_BRAND_MARGIN_BOOST
}
