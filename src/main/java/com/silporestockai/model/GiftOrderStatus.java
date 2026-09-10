package com.silporestockai.model;

/** Where one gift is in its life (task 81). Persisted by name, so entries may be added but not renamed. */
public enum GiftOrderStatus {
    /** The recipient was asked for an address in their own chat and has not answered yet. */
    AWAITING_ADDRESS,
    /** An address is in hand; no cart has been built from it yet. */
    RESOLVED,
    /** The cart is in front of the sender, and it is holding the household's own delivery settings. */
    CART_PRESENTED,
    /** The sender confirmed. The cart stays pointed at the friend until the household orders again. */
    CONFIRMED,
    /** Nobody answered inside the window; the sender was told. */
    EXPIRED,
    /** The named friend has never spoken to the bot, so there was no chat to ask in. */
    UNREACHABLE,
    /** The sender backed out, the recipient declined, or the household's own delivery block is back. */
    CANCELLED
}
