package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/**
 * The product choice for a cart could not be made — the model call behind {@code ProductMatchingService} failed.
 *
 * <p>Thrown rather than degraded to Silpo's own ranking on purpose. That fallback was exercised once for real,
 * during an API-credit outage, and produced a ₴7549 cart of konjac noodles, seventeen packets of beef jerky and a
 * ₴1399 cheese in front of the household, with a «Підтвердити» button under it. A cart that is wrong is worse
 * than no cart: the person can tap «Замовити» again a minute later; they cannot un-order jerky.
 */
public class ProductMatchException extends ApplicationException {

    public ProductMatchException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
