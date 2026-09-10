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
public record GiftRequest(String recipientUsername, String address, String flat, String phone, String theme) {}
