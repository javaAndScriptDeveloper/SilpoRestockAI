package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One call to one Silpo MCP tool (task 37). The evidence behind "how many of the 39 tools this agent really uses"
 * — a count over this table, not a number typed into a slide.
 */
@Entity
@Table(name = "mcp_tool_call")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolCall {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "is_error", nullable = false)
    private boolean error;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt;
}
