package com.silporestockai.service;

import com.silporestockai.entity.McpToolCall;
import com.silporestockai.model.McpToolCalledEvent;
import com.silporestockai.repository.McpToolCallRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Writes one {@code mcp_tool_call} row per {@link McpToolCalledEvent} (task 37).
 *
 * <p>Synchronous and swallowing its own failures: the row is evidence for a pitch, and a broken evidence log must
 * never turn a working cart build into a failed one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpCallLogService {

    private final McpToolCallRepository mcpToolCallRepository;

    @EventListener
    public void onToolCalled(McpToolCalledEvent event) {
        try {
            mcpToolCallRepository.save(McpToolCall.builder()
                    .id(UUID.randomUUID())
                    .toolName(event.toolName())
                    .userId(event.userId())
                    .error(event.error())
                    .calledAt(event.calledAt())
                    .build());
        } catch (RuntimeException e) {
            log.warn("could not log MCP tool call {}: {}", event.toolName(), e.getMessage());
        }
    }
}
