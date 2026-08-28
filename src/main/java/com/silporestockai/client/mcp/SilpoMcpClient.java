package com.silporestockai.client.mcp;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generic access to the Silpo MCP server. Callers name a tool and pass arguments; authentication, the session
 * handshake, refresh-on-401 and backoff-on-429 are handled here.
 *
 * <p>Typed per-tool wrappers deliberately do not live here — they belong to the services that use them.
 */
public interface SilpoMcpClient {

    /** The tools the server currently exposes for this user, fetched live. */
    List<McpToolInfo> listTools(UUID userId);

    /** Invokes a tool by name. */
    McpToolResponse callTool(String toolName, Map<String, Object> arguments, UUID userId);

    /** Drops the cached session for a user, e.g. after they disconnect their account. */
    void disconnect(UUID userId);
}
