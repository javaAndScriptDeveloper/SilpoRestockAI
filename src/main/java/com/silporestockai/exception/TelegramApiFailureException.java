package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Raised when the Telegram Bot API rejects or fails a call we made. */
public class TelegramApiFailureException extends ApplicationException {

    public TelegramApiFailureException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
