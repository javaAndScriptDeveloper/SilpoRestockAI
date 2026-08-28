package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Raised when a call to the Silpo MCP server fails for a reason the caller cannot fix. */
public class SilpoMcpException extends ApplicationException {

    public SilpoMcpException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }

    public SilpoMcpException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }

    protected SilpoMcpException(HttpStatus status, String message, Throwable cause) {
        super(status, message, cause);
    }
}
