package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Claude answered 429. One of the two exceptions the {@code claude} Resilience4j retry backs off on. */
public class ClaudeRateLimitedException extends ClaudeApiException {

    public ClaudeRateLimitedException(String message, Throwable cause) {
        super(HttpStatus.TOO_MANY_REQUESTS, message, cause);
    }
}
