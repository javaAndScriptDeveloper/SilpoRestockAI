package com.silporestockai.exception;

import java.util.List;

/**
 * The delivery window booked on the cart has gone, and Silpo has no other to offer (task 76).
 *
 * <p>Its own type because it is the one cart refusal the household can do nothing about and nothing about the
 * list would fix. Silpo answering «timeslot.not_available» is ordinarily recovered from — a fresh slot is picked
 * and booked, see {@code CartBuildingService.getVerifiedCart} — and this is what is left when even that finds no
 * window: a full branch, or the last slot of the day gone by. The sentence a person reads for it says so, instead
 * of the «виправ список» that a generic cart-build failure ends with.
 *
 * <p>A {@link CartBuildException} on purpose: every caller that already handles a failed build keeps working, and
 * the one place that writes the household's message catches this first.
 */
public class DeliverySlotUnavailableException extends CartBuildException {

    public DeliverySlotUnavailableException(String message, List<String> validations) {
        super(message, validations);
    }
}
