package com.silporestockai.exception;

import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Raised when a cart cannot be built at all — no cart id, or no deliverable time slot. */
@Getter
public class CartBuildException extends ApplicationException {

    /** Silpo's own reasons the cart can't check out (stock, timeslot), when the failure came with any. */
    private final List<String> validations;

    public CartBuildException(String message) {
        this(message, List.of());
    }

    public CartBuildException(String message, List<String> validations) {
        super(HttpStatus.BAD_GATEWAY, message);
        this.validations = validations == null ? List.of() : validations;
    }
}
