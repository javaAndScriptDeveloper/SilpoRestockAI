package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/** Raised when a cart cannot be built at all — no cart id, or no deliverable time slot. */
public class CartBuildException extends ApplicationException {

    public CartBuildException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }
}
