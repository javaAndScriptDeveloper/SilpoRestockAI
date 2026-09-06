package com.silporestockai.exception;

import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Raised when a cart cannot be built at all — no cart id, or no deliverable time slot. */
@Getter
public class CartBuildException extends ApplicationException {

    /** Silpo's own reasons the cart can't check out (stock, timeslot), when the failure came with any. */
    private final List<String> validations;

    /** What the cart added up to when Silpo refused it, when the refusal was about the amount. */
    private final BigDecimal total;

    /** The smallest order Silpo delivers, when that is what the refusal was about. */
    private final BigDecimal minimumOrder;

    public CartBuildException(String message) {
        this(message, List.of());
    }

    public CartBuildException(String message, List<String> validations) {
        this(message, validations, null, null);
    }

    public CartBuildException(String message, List<String> validations, BigDecimal total, BigDecimal minimumOrder) {
        super(HttpStatus.BAD_GATEWAY, message);
        this.validations = validations == null ? List.of() : validations;
        this.total = total;
        this.minimumOrder = minimumOrder;
    }

    /** Whether the cart was refused for being too small, which a caller may be able to do something about. */
    public boolean belowMinimumOrder() {
        return minimumOrder != null;
    }
}
