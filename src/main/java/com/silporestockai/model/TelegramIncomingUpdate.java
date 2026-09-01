package com.silporestockai.model;

/**
 * The parts of a Telegram update this application acts on, in terms that carry no Telegram SDK types.
 *
 * <p>Everything else Telegram can send (edits, polls, chat member changes) is dropped by the router.
 */
public sealed interface TelegramIncomingUpdate {

    /** The chat that produced the update, and the chat any reply goes back to. */
    long chatId();

    /** A plain text message. */
    record Text(long chatId, long telegramUserId, String text) implements TelegramIncomingUpdate {}

    /** A voice note. Only its file id is carried; fetching bytes is {@code TelegramOutboundService}'s job. */
    record Voice(long chatId, long telegramUserId, String fileId, int durationSeconds)
            implements TelegramIncomingUpdate {}

    /**
     * A photo. Telegram sends several sizes of the same picture; the router keeps the largest, because a model
     * reading a fridge needs the pixels.
     */
    record Photo(long chatId, long telegramUserId, String fileId, String mediaType) implements TelegramIncomingUpdate {}

    /** An inline keyboard button tap. {@code data} is the {@code callbackData} the button was built with. */
    record ButtonTap(long chatId, long telegramUserId, String callbackQueryId, String data)
            implements TelegramIncomingUpdate {}
}
