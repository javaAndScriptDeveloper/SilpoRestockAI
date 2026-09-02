package com.silporestockai.exception;

/**
 * A guest with no cart also has no saved Silpo delivery address to create one from. Distinct from
 * {@link CartBuildException} in general so the chat can say the one thing that actually fixes it — add an address in
 * the Silpo app — instead of the generic "try again later", which this is not.
 */
public class NoSilpoDeliveryAddressException extends CartBuildException {

    public NoSilpoDeliveryAddressException(String message) {
        super(message);
    }
}
