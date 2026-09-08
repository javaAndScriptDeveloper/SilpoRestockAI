package com.silporestockai.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The parts of a Telegram update this application acts on, in terms that carry no Telegram SDK types.
 *
 * <p>Private-chat shapes come first; the group-chat shapes (task 68) after them. Everything else Telegram can send
 * (edits, polls, reactions) is dropped by the router.
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
    record Photo(long chatId, long telegramUserId, String fileId, String mediaType, String caption)
            implements TelegramIncomingUpdate {

        /** The pre-task-36 shape: a photo with nothing written under it. */
        public Photo(long chatId, long telegramUserId, String fileId, String mediaType) {
            this(chatId, telegramUserId, fileId, mediaType, null);
        }

        public boolean hasCaption() {
            return caption != null && !caption.isBlank();
        }
    }

    /** An inline keyboard button tap. {@code data} is the {@code callbackData} the button was built with. */
    record ButtonTap(long chatId, long telegramUserId, String callbackQueryId, String data)
            implements TelegramIncomingUpdate {}

    /** A Telegram WebApp form submission — {@code Telegram.WebApp.sendData()} on the client side. */
    record WebAppData(long chatId, long telegramUserId, String data) implements TelegramIncomingUpdate {}

    /**
     * A text message in a group chat (task 68).
     *
     * <p>A group is noisy and most of what is said there is not for the bot, so the record carries the three
     * ways a message can be aimed at it — a reply to one of the bot's own messages, an {@code @mention}, a
     * {@code /command} — and the handler ignores anything that is none of them. In privacy mode Telegram delivers
     * only those anyway; when the bot is an admin it delivers everything, and this is the line that keeps the
     * behaviour identical.
     *
     * @param replyToBotMessageId the id of the bot message this one replies to, or null when it replies to
     *     nothing or to a person
     * @param mentionsBot whether the text carries an {@code @mention} of this bot
     * @param command a {@code /command} aimed at this bot (or at nobody in particular), lower-cased and without
     *     the {@code @bot} suffix, or null
     */
    record GroupText(
            long chatId,
            long telegramUserId,
            String displayName,
            int messageId,
            String text,
            Integer replyToBotMessageId,
            boolean mentionsBot,
            String command)
            implements TelegramIncomingUpdate {

        public boolean addressedToBot() {
            return replyToBotMessageId != null || mentionsBot || command != null;
        }

        /** The words for the bot: the text with its {@code @bot} mention and {@code /command} token removed. */
        public String bodyWithoutAddress(String botUsername) {
            String body = text == null ? "" : text;
            if (botUsername != null && !botUsername.isBlank()) {
                body = Pattern.compile("@" + Pattern.quote(botUsername), Pattern.CASE_INSENSITIVE)
                        .matcher(body)
                        .replaceAll(" ");
            }
            if (command != null) {
                body = Pattern.compile(
                                "(?i)(^|\\s)" + Pattern.quote(command) + "(@\\S+)?(?=\\s|$)", Pattern.UNICODE_CASE)
                        .matcher(body)
                        .replaceFirst(" ");
            }
            return body.replaceAll("\\s+", " ").strip();
        }

        /** Whether the text is a reply to the given bot message. */
        public boolean repliesTo(Integer botMessageId) {
            return botMessageId != null && replyToBotMessageId != null && replyToBotMessageId.equals(botMessageId);
        }
    }

    /** An inline button tap on one of the bot's messages in a group chat (task 68). */
    record GroupButtonTap(
            long chatId, long telegramUserId, String displayName, String callbackQueryId, String data, int messageId)
            implements TelegramIncomingUpdate {}

    /**
     * The bot was added to a group (task 68). {@code byTelegramUserId} is the person who performed the add — the
     * organizer, read from Telegram's own event rather than guessed from anything that follows.
     */
    record BotAddedToGroup(long chatId, String chatTitle, long byTelegramUserId, String byDisplayName)
            implements TelegramIncomingUpdate {}

    /** The bot was removed from a group, or left it. */
    record BotRemovedFromGroup(long chatId) implements TelegramIncomingUpdate {}

    /** Whether a chat type string from Telegram names a group of any kind. */
    static boolean isGroupChatType(String chatType) {
        if (chatType == null) {
            return false;
        }
        String type = chatType.toLowerCase(Locale.ROOT);
        return "group".equals(type) || "supergroup".equals(type);
    }
}
