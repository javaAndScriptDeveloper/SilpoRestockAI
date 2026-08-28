package com.silporestockai.client.mcp;

import java.util.List;

/**
 * The result of a {@code tools/call}.
 *
 * @param text concatenated text content blocks, the form most Silpo tools answer in
 * @param structuredContent the server's structured result when it sends one, otherwise null
 * @param isError true when the server reported a tool-level error rather than a transport failure
 */
public record McpToolResponse(String text, Object structuredContent, boolean isError) {

    public static McpToolResponse of(List<String> textBlocks, Object structuredContent, boolean isError) {
        return new McpToolResponse(String.join("\n", textBlocks), structuredContent, isError);
    }
}
