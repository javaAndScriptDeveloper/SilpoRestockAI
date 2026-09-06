package com.silporestockai.repository;

import com.silporestockai.entity.McpToolCall;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpToolCallRepository extends JpaRepository<McpToolCall, UUID> {}
