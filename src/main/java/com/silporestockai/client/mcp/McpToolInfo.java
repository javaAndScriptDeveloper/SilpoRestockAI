package com.silporestockai.client.mcp;

import java.util.Map;

/**
 * One tool as advertised by the live {@code tools/list} call.
 *
 * <p>Nothing in this codebase hardcodes the tool catalogue from the documentation — the schema here is whatever the
 * server returns at runtime.
 *
 * @param name tool name, e.g. {@code silpo_find_products_batch}
 * @param description human-readable description from the server
 * @param inputSchema JSON Schema for the tool's arguments
 */
public record McpToolInfo(String name, String description, Map<String, Object> inputSchema) {}
