package com.silporestockai.model;

/**
 * What a «надішли подарунок …» sentence turned out to be asking for (task 81).
 *
 * @param recipientUsername the {@code @nickname} named, without the {@code @}, or null when an address was given
 *     instead
 * @param address a street address the sender typed outright, or null
 * @param flat the apartment the sender mentioned, or null
 * @param phone a number the sender gave for the recipient, or null
 * @param theme what to buy, in the sender's own words
 */
public record GiftRequest(String recipientUsername, String address, String flat, String phone, String theme) {

    /**
     * The same request with the model's ways of saying «nothing» turned into real nulls.
     *
     * <p>Live on 2026-09-10 the structured call answered {@code phone=".null"} for a sentence that named no
     * number, and that string went all the way onto the cart as a courier's phone. A plain null check does not
     * catch it, and neither does {@code isBlank}. Cleaned once here, at the boundary where model output enters
     * the system, rather than at each of the five places that ask whether a field was filled in.
     */
    public GiftRequest cleaned() {
        return new GiftRequest(clean(recipientUsername), clean(address), clean(flat), clean(phone), clean(theme));
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.equalsIgnoreCase("null") || trimmed.equalsIgnoreCase(".null") ? null : trimmed;
    }
}
