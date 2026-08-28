package com.silporestockai.client.mcp;

import java.util.UUID;

/**
 * Supplies Silpo MCP access tokens to the transport layer.
 *
 * <p>The port lives in the client package and is implemented by {@code service.SilpoAuthService}, inverting the
 * dependency: ArchUnit only lets a {@code Service} be reached from a {@code Controller} or a {@code Job}, so the
 * MCP client must not depend on the service directly. It also keeps the client trivially testable with a stub.
 *
 * <p>Implementations must never log or otherwise expose the token they return.
 */
public interface SilpoAccessTokenProvider {

    /**
     * The current access token for the user, refreshing first if it is already expired.
     *
     * @throws com.silporestockai.exception.SilpoNotConnectedException if the user has never completed the OAuth login
     */
    String accessToken(UUID userId);

    /**
     * Force a refresh after the server rejected the current token.
     *
     * @return true if a new access token was obtained and a retry is worth attempting
     */
    boolean refresh(UUID userId);
}
