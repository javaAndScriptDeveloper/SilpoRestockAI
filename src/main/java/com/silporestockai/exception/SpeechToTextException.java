package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Raised when a voice note cannot be turned into text — no key configured, or the service refused. */
public class SpeechToTextException extends ApplicationException {

    public SpeechToTextException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }

    public SpeechToTextException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
