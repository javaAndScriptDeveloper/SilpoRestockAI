package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One value pool's placements added up (task 64): the number a jury member reads before asking about any particular
 * brand.
 *
 * <p>The denominator is deliberately narrow. A share taken over every resolution ever logged would count the bread
 * and the eggs nobody bids on, and would sink towards zero as the household's list grew — describing the list, not
 * the placements. Counting only the categories the pool actually holds a placement in answers the question somebody
 * is really asking: «серед категорій, де ви стоїте, скільки ви забрали».
 *
 * @param type the pool, or null for both pools together
 * @param featuredShareRate featured over category resolutions, or null when nothing in those categories resolved yet
 * @param categoryResolutions resolutions in the categories this pool holds, each counted once however many
 *     placements of the pool sit on that category
 * @param activeCategories distinct categories with an {@code ACTIVE} placement — a paused placement holds no shelf
 */
public record PromotionRollup(
        PromotionType type,
        Double featuredShareRate,
        long featuredResolutions,
        long categoryResolutions,
        BigDecimal attributedRevenue,
        int activeCategories) {}
