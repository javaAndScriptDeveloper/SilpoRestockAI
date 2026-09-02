package com.silporestockai.service.telegram;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

/**
 * The persistent bottom keyboard, offered once when onboarding finishes. Telegram keeps a
 * {@code ReplyKeyboardMarkup} showing under the text box for the rest of the chat's life once sent, so this needs
 * sending only at the one moment these commands first become usable — not attached to every message.
 *
 * <p>Each label is also what {@link TelegramRoutingService} matches on: tapping a button sends its own text back as
 * an ordinary message, exactly as if it had been typed, so the slash commands underneath are unchanged and still
 * work for anyone who prefers typing them.
 */
public final class MainMenuKeyboard {

    public static final String LIST = "📝 Список";
    public static final String REORDER = "🔁 Замовити ще";
    public static final String VOICE = "🎙 Голосові";
    public static final String BLACKOUT = "🌙 Блекаут";
    public static final String CALENDAR = "📅 Календар";

    private MainMenuKeyboard() {}

    public static ReplyKeyboardMarkup markup() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(LIST, REORDER))
                .keyboardRow(new KeyboardRow(VOICE, BLACKOUT))
                .keyboardRow(new KeyboardRow(CALENDAR))
                .resizeKeyboard(true)
                .build();
    }
}
