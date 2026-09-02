package com.silporestockai.model;

import java.util.UUID;

/**
 * Published when a guest finishes the Silpo OAuth login in their browser.
 *
 * <p>This exists because of a Telegram detail: the "connect" button is a URL button, and tapping one sends nothing
 * back to the bot. Without this event the conversation waits at {@code AWAITING_CONNECT} for a callback that can
 * never arrive, while the tokens sit happily in the database.
 *
 * @param userId whose account was connected
 */
public record SilpoConnectedEvent(UUID userId) {}
