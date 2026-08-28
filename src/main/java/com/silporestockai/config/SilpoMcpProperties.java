package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Silpo MCP integration.
 *
 * <p>Values match what {@code https://mcp.silpo.ua/.well-known/oauth-authorization-server} and
 * {@code /.well-known/oauth-protected-resource/mcp} advertise. The server is a public OAuth 2.1 client
 * ({@code token_endpoint_auth_method: none}), so there is no client secret anywhere in this config.
 *
 * @param endpoint the MCP Streamable HTTP endpoint
 * @param issuer the OAuth authorization server base URL (hosts /register, /authorize, /token)
 * @param resource RFC 8707 resource indicator — the MCP endpoint URL, not the bare host
 * @param clientId client id from Dynamic Client Registration; blank triggers registration on first use
 * @param clientName client name sent during Dynamic Client Registration
 * @param redirectUri OAuth redirect, must match what was registered
 * @param requestTimeout per-MCP-request timeout
 * @param loginStateTtl how long an unused PKCE login state stays valid
 * @param tokenEncryptionKey base64-encoded 32-byte AES key; blank generates an ephemeral key at startup
 */
@ConfigurationProperties(prefix = "silpo.mcp")
public record SilpoMcpProperties(
        String endpoint,
        String issuer,
        String resource,
        String clientId,
        String clientName,
        String redirectUri,
        Duration requestTimeout,
        Duration loginStateTtl,
        String tokenEncryptionKey) {

    public String registrationEndpoint() {
        return issuer + "/register";
    }

    public String authorizationEndpoint() {
        return issuer + "/authorize";
    }

    public String tokenEndpoint() {
        return issuer + "/token";
    }
}
