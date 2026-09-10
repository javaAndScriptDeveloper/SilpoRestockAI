package com.silporestockai.model;

/** How a gift's destination was arrived at (task 81). Persisted by name. */
public enum GiftResolution {
    /** The sender typed the address themselves. */
    DIRECT,
    /** The recipient had already opted in, so their stored address and phone were used untouched. */
    CONSENTED,
    /** The recipient was asked, in their own chat, and answered. */
    ASKED
}
