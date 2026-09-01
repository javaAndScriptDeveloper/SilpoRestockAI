package com.silporestockai.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A Google consent screen that has been opened but not returned from, keyed by the opaque {@code state} value that
 * travels through the browser.
 *
 * <p>Unlike {@link SilpoLoginState} it holds nothing secret — Google's web flow uses a client secret held in
 * configuration rather than a PKCE verifier — but it still lives server-side, because the state value is what proves
 * the callback belongs to a login this application started.
 *
 * @param userId the user who started the login
 * @param startedAt when they started it, used for expiry
 */
public record GoogleLoginState(UUID userId, Instant startedAt) {}
