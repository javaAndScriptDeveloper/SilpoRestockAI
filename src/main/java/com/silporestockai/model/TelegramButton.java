package com.silporestockai.model;

/**
 * One inline keyboard button, in terms the rest of the app can use without the Telegram SDK.
 *
 * @param label text shown on the button
 * @param callbackData opaque payload Telegram sends back in the callback query, at most 64 bytes
 */
public record TelegramButton(String label, String callbackData) {}
