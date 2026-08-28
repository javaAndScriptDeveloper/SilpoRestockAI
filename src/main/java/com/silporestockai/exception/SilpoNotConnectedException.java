package com.silporestockai.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Raised when a user has no usable Silpo MCP token — they have not completed the OAuth login yet. */
public class SilpoNotConnectedException extends ApplicationException {

    public SilpoNotConnectedException(UUID userId) {
        super(HttpStatus.PRECONDITION_REQUIRED, "user %s has not connected their Silpo account".formatted(userId));
    }
}
