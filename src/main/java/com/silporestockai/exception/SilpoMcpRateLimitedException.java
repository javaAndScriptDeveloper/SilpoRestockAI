package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when the Silpo MCP server answers {@code 429}. This is the only exception
 * {@code resilience4j.retry.instances.silpoMcp} retries, so nothing else may extend it.
 */
public class SilpoMcpRateLimitedException extends SilpoMcpException {

    public SilpoMcpRateLimitedException(String message, Throwable cause) {
        super(HttpStatus.TOO_MANY_REQUESTS, message, cause);
    }
}
