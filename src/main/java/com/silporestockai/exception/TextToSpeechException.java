package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Raised when text cannot be turned into speech. Never fatal: the written message has already been sent. */
public class TextToSpeechException extends ApplicationException {

    public TextToSpeechException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }

    public TextToSpeechException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
