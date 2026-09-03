package com.silporestockai.model;

/**
 * One inline keyboard button, in terms the rest of the app can use without the Telegram SDK.
 *
 * <p>A button carries exactly one of a callback, a URL, or (kept separate because a reply-keyboard WebApp button is
 * built differently from either) a WebApp URL — the factories are the only way to build one and each sets exactly
 * one.
 *
 * @param label text shown on the button
 * @param callbackData opaque payload Telegram sends back in the callback query, at most 64 bytes; null unless this is
 *     a callback button
 * @param url address the button opens; null unless this is a link button
 * @param webAppUrl address of a Telegram WebApp to open; null unless this is a WebApp button
 */
public record TelegramButton(String label, String callbackData, String url, String webAppUrl) {

    /** A button that sends {@code data} back to the bot when tapped. */
    public static TelegramButton callback(String label, String data) {
        return new TelegramButton(label, data, null, null);
    }

    /** A button that opens {@code url}. Used for the Silpo OAuth hand-off, which leaves Telegram. */
    public static TelegramButton link(String label, String url) {
        return new TelegramButton(label, null, url, null);
    }

    /** A button that opens a Telegram WebApp at {@code webAppUrl}. */
    public static TelegramButton webApp(String label, String webAppUrl) {
        return new TelegramButton(label, null, null, webAppUrl);
    }
}
