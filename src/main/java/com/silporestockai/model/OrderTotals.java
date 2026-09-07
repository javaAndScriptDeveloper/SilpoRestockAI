package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * Confirmed-order money and counts for one {@link OrderType}, aggregated by the database (task 54).
 *
 * <p>A JPQL constructor expression rather than five separate count/sum queries: the observability refresh runs on a
 * schedule forever, and one grouped pass over {@code customer_order} is what keeps a Prometheus scrape from costing
 * anything. {@code valueMissing} counts confirmed orders whose total is null or zero — rows written before task 54's
 * columns existed, plus any cart Silpo answered without a total. It is published as its own gauge so the dashboard
 * states the coverage of its own GMV number instead of quietly averaging over holes.
 *
 * @param type which kind of order these totals are for
 * @param count how many confirmed orders of that type exist
 * @param total sum of what the households were billed, delivery included
 * @param goodsTotal sum of merchandise only
 * @param savings sum of what promotions took off
 * @param valueMissing how many of {@code count} carry no usable total
 */
public record OrderTotals(
        OrderType type, long count, BigDecimal total, BigDecimal goodsTotal, BigDecimal savings, long valueMissing) {}
