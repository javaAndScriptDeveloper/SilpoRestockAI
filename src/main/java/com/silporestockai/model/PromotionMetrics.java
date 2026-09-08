package com.silporestockai.model;

import com.silporestockai.entity.PartnerPromotion;
import java.math.BigDecimal;

/**
 * What one placement achieved (task 63): the funnel task 46 already counted, plus the share of its category it
 * actually took and how much of that share was the placement's own doing.
 *
 * @param featuredResolutions lines in this category the placement answered
 * @param categoryResolutions lines in this category resolved at all — the denominator, including the lines the
 *     placement could never have won because the household's restrictions ruled it out
 * @param featuredShareRate featured over category, or null when the category has no resolutions yet
 * @param baselineShare the organic share, or null when {@code baselineMethod} is {@code UNKNOWN}
 * @param attributedRevenue hryvnia of the promoted product's own lines in the orders that confirmed it (task 64) —
 *     the placement's money, never the whole basket's, and never an estimate
 * @param ordersMissingPrice promoted lines in those orders that carried no stored price. They are left out of the
 *     sum and counted here instead: a line silently added as zero would under-report and still look precise
 */
public record PromotionMetrics(
        PartnerPromotion promotion,
        long impressions,
        long addedToCart,
        long confirmedOrders,
        long featuredResolutions,
        long categoryResolutions,
        Double featuredShareRate,
        Double baselineShare,
        BaselineMethod baselineMethod,
        BigDecimal attributedRevenue,
        long ordersMissingPrice) {

    /** Share the placement added over the baseline, as a fraction — null unless both numbers are real. */
    public Double lift() {
        return featuredShareRate == null || baselineShare == null ? null : featuredShareRate - baselineShare;
    }
}
