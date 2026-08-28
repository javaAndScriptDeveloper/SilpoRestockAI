package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Claude was unreachable or answered 5xx. Worth retrying, unlike a malformed request. */
public class ClaudeUnavailableException extends ClaudeApiException {

    public ClaudeUnavailableException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
