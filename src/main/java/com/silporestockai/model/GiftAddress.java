package com.silporestockai.model;

/**
 * Where a gift goes, in the recipient's own words (task 81).
 *
 * <p>{@code addressText} is what {@code silpo_find_address} is asked to geocode — a city, a street and a house
 * number. The rest is what geocoding cannot give back and a courier still needs: the apartment, the entrance, the
 * floor and a number to call. Probed live on 2026-09-10; {@code silpo_update_shopping_cart} keeps all four on the
 * cart's address object verbatim.
 */
public record GiftAddress(String addressText, String flat, String entrance, String floor, String phone) {

    /** The address and a number, for the path where the sender typed both and nothing else is known. */
    public static GiftAddress of(String addressText, String phone) {
        return new GiftAddress(addressText, null, null, null, phone);
    }
}
