package com.silporestockai.model;

import java.time.Duration;
import java.util.List;

/**
 * Everything the DB-derived Prometheus gauges publish, read once per refresh interval (task 54).
 *
 * <p>Why a snapshot at all: a Micrometer {@code Counter} is monotonic only within one JVM, and this app is restarted
 * constantly. "Households registered" and "GMV" are facts about the database, not about this process — after a
 * {@code make run} they must still read what the tables say, not zero. So the absolute levels are gauges over a value
 * refreshed on a schedule, and a Prometheus scrape reads memory rather than running a query.
 *
 * @param usersRegistered rows in {@code users}
 * @param usersOnboarded rows in {@code user_profile} — a finished profile, not a started one
 * @param usersActive households with a conversation turn inside the configured window
 * @param usersOrdered distinct households with at least one confirmed order
 * @param orders per-type confirmed counts and money
 * @param orderItemLines total basket lines across all confirmed orders
 * @param resolvedLines cart lines Silpo matched a product for, all-time
 * @param unresolvedLines cart lines it did not
 * @param medianToFirstOrder median onboarding→first confirmed order, or null when nobody has ordered yet
 * @param fastestToFirstOrder the quickest one, or null
 * @param promotions per-placement funnel counts
 */
public record ObservabilitySnapshot(
        long usersRegistered,
        long usersOnboarded,
        long usersActive,
        long usersOrdered,
        List<OrderTotals> orders,
        long orderItemLines,
        long resolvedLines,
        long unresolvedLines,
        Duration medianToFirstOrder,
        Duration fastestToFirstOrder,
        List<PromotionEventCount> promotions) {

    /** The value the gauges are registered against before the first refresh runs — all zero, nothing null. */
    public static ObservabilitySnapshot empty() {
        return new ObservabilitySnapshot(0, 0, 0, 0, List.of(), 0, 0, 0, null, null, List.of());
    }
}
