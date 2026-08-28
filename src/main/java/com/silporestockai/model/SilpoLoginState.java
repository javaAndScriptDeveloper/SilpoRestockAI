package com.silporestockai.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending OAuth login: the PKCE verifier is held server-side between {@code /auth/silpo/start} and the callback,
 * keyed by the opaque {@code state} value that travels through the browser.
 *
 * <p>{@code toString} is overridden so the verifier cannot reach a log line.
 *
 * @param userId the user who started the login
 * @param codeVerifier the PKCE code verifier whose S256 hash was sent as the challenge
 * @param createdAt when the login started, used for expiry
 */
public record SilpoLoginState(UUID userId, String codeVerifier, Instant createdAt) {

    @Override
    public String toString() {
        return "SilpoLoginState[userId=%s, createdAt=%s]".formatted(userId, createdAt);
    }
}
