package com.silporestockai.exception;

import org.springframework.http.HttpStatus;

/**
 * Silpo delivers nothing to the address a gift was aimed at — either the geocoder placed nowhere, or nowhere it
 * placed is inside a home-delivery polygon.
 *
 * <p>A real answer rather than a failure to hide: the sender is told, and asked for another address.
 */
public class GiftDeliveryUnavailableException extends ApplicationException {

    public GiftDeliveryUnavailableException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
