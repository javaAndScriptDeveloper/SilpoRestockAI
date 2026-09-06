package com.silporestockai.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the MCP client after every tool call, success or error (task 37).
 *
 * <p>An event rather than a repository call because the client layer may not reach the repository layer
 * (ArchUnit); {@code McpCallLogService} is the listener that writes the row.
 *
 * @param toolName the Silpo tool that was called
 * @param userId whose session it ran in
 * @param error whether Silpo answered with {@code isError}
 * @param calledAt when
 */
public record McpToolCalledEvent(String toolName, UUID userId, boolean error, Instant calledAt) {}
