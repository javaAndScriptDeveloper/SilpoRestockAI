package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a Claude API call fails for a reason the caller cannot fix by retrying.
 *
 * <p>Never carries the API key: the SDK keeps it in a header, and the messages built here quote only the API's own
 * error text.
 */
public class ClaudeApiException extends ApplicationException {

    public ClaudeApiException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }

    public ClaudeApiException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }

    protected ClaudeApiException(HttpStatus status, String message, Throwable cause) {
        super(status, message, cause);
    }
}
