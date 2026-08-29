package com.silporestockai.model;

/**
 * One inline keyboard button, in terms the rest of the app can use without the Telegram SDK.
 *
 * <p>A button either sends a callback back to the bot or opens a URL. Telegram rejects a button that carries both, so
 * the factories are the only way to build one and each sets exactly one.
 *
 * @param label text shown on the button
 * @param callbackData opaque payload Telegram sends back in the callback query, at most 64 bytes; null for a link
 *     button
 * @param url address the button opens; null for a callback button
 */
public record TelegramButton(String label, String callbackData, String url) {

    /** A button that sends {@code data} back to the bot when tapped. */
    public static TelegramButton callback(String label, String data) {
        return new TelegramButton(label, data, null);
    }

    /** A button that opens {@code url}. Used for the Silpo OAuth hand-off, which leaves Telegram. */
    public static TelegramButton link(String label, String url) {
        return new TelegramButton(label, null, url);
    }
}
