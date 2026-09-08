package com.silporestockai.model;

/**
 * How many times one partner placement produced one kind of event (task 46's funnel, task 54's panel).
 *
 * <p>Carries the partner and product names rather than the promotion id, because a Prometheus tag has to be readable
 * on a dashboard a partner is shown — {@code partner="Моршинська"} tells a story that a UUID does not. Tag cardinality
 * stays bounded by the number of paid placements, which is a handful.
 *
 * @param partner whose placement it is
 * @param product the promoted product's name
 * @param category the list line the placement answers, so a funnel panel can be read per category
 * @param type which value pool this is — paid external revenue or Silpo's own margin (task 64 splits the panels on
 *     it, and the two must never be added into one bar)
 * @param eventType impression, added to cart, or confirmed in an order
 * @param count how many of them there are
 */
public record PromotionEventCount(
        String partner,
        String product,
        String category,
        PromotionType type,
        PartnerPromotionEventType eventType,
        long count) {}
