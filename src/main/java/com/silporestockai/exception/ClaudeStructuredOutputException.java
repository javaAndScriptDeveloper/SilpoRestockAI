package com.silporestockai.exception;

/**
 * The model did not return output matching the requested type.
 *
 * <p>Deliberately not retried: whether to ask again with a different prompt or fall back is the caller's decision, and
 * silently retrying would hide a prompt or schema problem.
 */
public class ClaudeStructuredOutputException extends ClaudeApiException {

    public ClaudeStructuredOutputException(String message) {
        super(message);
    }

    public ClaudeStructuredOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
