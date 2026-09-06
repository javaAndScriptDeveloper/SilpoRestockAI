package com.silporestockai.model;

/** The funnel a partner is sold (task 46): featured → in a built cart → in a confirmed order. */
public enum PartnerPromotionEventType {
    /** The promoted product was chosen as the match for a line the household asked for. */
    IMPRESSION,
    /** That resolved line went into a cart Silpo built. */
    ADDED_TO_CART,
    /** The product was still in the cart the person confirmed. */
    CONFIRMED_ORDER
}
