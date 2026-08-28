package com.silporestockai.client.mcp;

import com.silporestockai.config.SilpoMcpProperties;
import com.silporestockai.exception.SilpoMcpException;
import com.silporestockai.exception.SilpoMcpRateLimitedException;
import io.github.resilience4j.retry.annotation.Retry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Streamable HTTP MCP client for {@code mcp.silpo.ua}.
 *
 * <p>Deliberately the one integration that is not a Feign client: the transport negotiates an {@code Mcp-Session-Id}
 * and may answer over {@code text/event-stream}, neither of which Feign can express.
 *
 * <p>Failure handling maps onto what the SDK actually does, verified against its sources:
 *
 * <ul>
 *   <li><b>401</b> — the transport raises an authorization error and consults the configured handler, which asks the
 *       token provider to refresh exactly once. The handler always declines the SDK's own replay: that replay reuses
 *       the {@link java.net.http.HttpRequest} it already built, stale {@code Authorization} header included, so a
 *       refreshed token would never reach the server. The call is instead retried once here, on a fresh session.
 *   <li><b>403</b> — the token is fine but the tool is not granted. Refreshing cannot help, so it is not attempted.
 *   <li><b>429</b> — the transport has no special case for it and throws a generic transport exception carrying the
 *       status code in its message. It is remapped to {@link SilpoMcpRateLimitedException}, the only exception the
 *       {@code silpoMcp} Resilience4j retry backs off on.
 * </ul>
 *
 * <p>The bearer token is injected per request and never logged; neither is the SDK's request snapshot, which carries
 * the {@code Authorization} header.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SilpoMcpClientImpl implements SilpoMcpClient {

    /** The SDK reports a 429 as {@code "Invalid request. Status code: 429"} — there is no typed exception for it. */
    private static final Pattern RATE_LIMITED = Pattern.compile("[Ss]tatus code:\\s*429");

    private static final String CLIENT_NAME = "komora";
    private static final String CLIENT_VERSION = "0.0.1";

    private final SilpoMcpProperties properties;
    private final SilpoAccessTokenProvider tokenProvider;

    private final Map<UUID, McpSyncClient> sessions = new ConcurrentHashMap<>();

    /** Marks users whose token the authorization handler refreshed, so {@link #execute} knows to try once more. */
    private final Map<UUID, Boolean> refreshedTokens = new ConcurrentHashMap<>();

    @Override
    @Retry(name = "silpoMcp")
    public List<McpToolInfo> listTools(UUID userId) {
        return execute(
                userId,
                session -> session.listTools().tools().stream()
                        .map(tool -> new McpToolInfo(tool.name(), tool.description(), tool.inputSchema()))
                        .toList());
    }

    @Override
    @Retry(name = "silpoMcp")
    public McpToolResponse callTool(String toolName, Map<String, Object> arguments, UUID userId) {
        log.debug("calling Silpo MCP tool {} for user {}", toolName, userId);
        return execute(userId, session -> {
            McpSchema.CallToolResult result = session.callTool(
                    new McpSchema.CallToolRequest(toolName, arguments == null ? Map.of() : arguments, null));
            List<String> textBlocks = new ArrayList<>();
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text) {
                    textBlocks.add(text.text());
                }
            }
            boolean isError = Boolean.TRUE.equals(result.isError());
            if (isError) {
                log.warn("Silpo MCP tool {} reported an error for user {}", toolName, userId);
            }
            return McpToolResponse.of(textBlocks, result.structuredContent(), isError);
        });
    }

    @Override
    public void disconnect(UUID userId) {
        closeSession(sessions.remove(userId), userId);
    }

    @PreDestroy
    void closeAllSessions() {
        sessions.forEach((userId, session) -> closeSession(session, userId));
        sessions.clear();
    }

    private <T> T execute(UUID userId, java.util.function.Function<McpSyncClient, T> action) {
        refreshedTokens.remove(userId);
        try {
            return action.apply(session(userId));
        } catch (RuntimeException e) {
            // The session may be holding a dead token or a closed server session; drop it so the next call rebuilds.
            disconnect(userId);
            if (refreshedTokens.remove(userId) == null) {
                throw translate(e);
            }
            log.debug("replaying the Silpo MCP call for user {} on a session built with the refreshed token", userId);
            try {
                return action.apply(session(userId));
            } catch (RuntimeException afterRefresh) {
                disconnect(userId);
                throw translate(afterRefresh);
            }
        }
    }

    private McpSyncClient session(UUID userId) {
        return sessions.computeIfAbsent(userId, this::openSession);
    }

    private McpSyncClient openSession(UUID userId) {
        URI endpoint = URI.create(properties.endpoint());
        String baseUri = endpoint.getScheme() + "://" + endpoint.getAuthority();
        String path = endpoint.getRawPath() == null || endpoint.getRawPath().isBlank() ? "/mcp" : endpoint.getRawPath();

        var transport = HttpClientStreamableHttpTransport.builder(baseUri)
                .endpoint(path)
                .connectTimeout(properties.requestTimeout())
                .openConnectionOnStartup(false)
                .httpRequestCustomizer((requestBuilder, method, uri, body, context) -> {
                    requestBuilder.header("Authorization", "Bearer " + tokenProvider.accessToken(userId));
                    // Rate limits are bucketed per guest by this cookie; our internal user id is the stable key.
                    requestBuilder.header("Cookie", "mcp-user=" + userId);
                })
                .authorizationErrorHandler(McpHttpClientTransportAuthorizationErrorHandler.fromSync(
                        (snapshot, responseInfo, context) -> {
                            if (responseInfo.statusCode() != 401) {
                                // 403 means the tool is not granted for this guest — a new token would not change that.
                                log.warn(
                                        "Silpo MCP refused the call for user {} with status {}",
                                        userId,
                                        responseInfo.statusCode());
                                return false;
                            }
                            log.info("Silpo MCP token rejected for user {}; refreshing once", userId);
                            if (tokenProvider.refresh(userId)) {
                                refreshedTokens.put(userId, Boolean.TRUE);
                            }
                            // Always decline the SDK's replay: it re-sends the request it already built, so the
                            // refreshed token would not be on it. execute() retries on a fresh session instead.
                            return false;
                        }))
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(properties.requestTimeout())
                .initializationTimeout(properties.requestTimeout())
                .clientInfo(new McpSchema.Implementation(CLIENT_NAME, null, CLIENT_VERSION, null, null, null))
                .build();

        client.initialize();
        // The live catalogue is the only authority on what is callable; the count also doubles as smoke-test output.
        log.info(
                "connected to Silpo MCP for user {} — {} tools available",
                userId,
                client.listTools().tools().size());
        return client;
    }

    private void closeSession(McpSyncClient session, UUID userId) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException e) {
            log.debug("failed to close the Silpo MCP session for user {}: {}", userId, e.getMessage());
        }
    }

    private RuntimeException translate(RuntimeException e) {
        if (e instanceof SilpoMcpException || e instanceof com.silporestockai.exception.SilpoNotConnectedException) {
            return e;
        }
        String message = String.valueOf(e.getMessage());
        if (RATE_LIMITED.matcher(message).find()) {
            return new SilpoMcpRateLimitedException("Silpo MCP rate limit hit", e);
        }
        // Deliberately not including the SDK's request snapshot: it carries the Authorization header.
        return new SilpoMcpException("Silpo MCP call failed: " + message, e);
    }
}
