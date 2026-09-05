package com.silporestockai.service.telegram;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

/**
 * The persistent bottom keyboard: task 31 shrank it to three navigation-or-nothing buttons, task 33 added a
 * fourth (Заплановані) for the one thing that is genuinely a management view rather than a chat intent.
 * Everything else is free text through {@link IntentRouterService}. {@link TelegramRoutingService}'s
 * slash-command branches for the retired buttons (blackout, reorder, voice, calendar, normal) still work if
 * typed — only the visible keyboard shrank; see {@code docs/OVERNIGHT_QUESTIONS.md}'s "Task 31" entry for why.
 */
public final class MainMenuKeyboard {

    public static final String LIST = "📝 Список";
    public static final String SCHEDULED = "🗓 Заплановані";
    public static final String FORM = "🧾 Анкета";
    public static final String HELP = "❓ Інструкція";

    private MainMenuKeyboard() {}

    public static ReplyKeyboardMarkup markup() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(LIST, SCHEDULED, FORM, HELP))
                .resizeKeyboard(true)
                .build();
    }
}
