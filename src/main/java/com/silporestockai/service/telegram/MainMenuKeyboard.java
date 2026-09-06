package com.silporestockai.service.telegram;

import java.util.List;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

/**
 * The persistent bottom keyboard: task 31 shrank it to three navigation-or-nothing buttons, task 33 added a
 * fourth (Заплановані) for the one thing that is genuinely a management view rather than a chat intent, task
 * 47 a fifth (Фідбек) — a message to us, not to the agent, so it cannot be an intent either. Everything else
 * is free text through {@link IntentRouterService}. {@link TelegramRoutingService}'s slash-command branches
 * for the retired buttons (blackout, reorder, voice, calendar, normal) still work if typed — only the visible
 * keyboard shrank; see {@code docs/OVERNIGHT_QUESTIONS.md}'s "Task 31" entry for why.
 */
public final class MainMenuKeyboard {

    public static final String LIST = "📝 Список";
    public static final String SCHEDULED = "🗓 Заплановані";
    public static final String FORM = "🧾 Анкета";
    public static final String HELP = "❓ Інструкція";
    public static final String FEEDBACK = "💬 Фідбек";

    private static final List<String> LABELS = List.of(LIST, SCHEDULED, FORM, HELP, FEEDBACK);

    private MainMenuKeyboard() {}

    /**
     * Two rows of two, then the feedback button on its own. Four labels in one row get squeezed to a third of
     * a phone's width each, and Telegram truncates the longer ones («Заплановані», «Інструкція») with an
     * ellipsis — the button then reads as "Заплан…", which is not a name anyone recognises on a demo recording.
     * Feedback sits apart because it is the one button that is not about groceries.
     */
    public static ReplyKeyboardMarkup markup() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(LIST, SCHEDULED))
                .keyboardRow(new KeyboardRow(FORM, HELP))
                .keyboardRow(new KeyboardRow(FEEDBACK))
                .resizeKeyboard(true)
                .build();
    }

    /** True when the text is exactly one of the persistent-menu labels — a navigation tap, not a sentence. */
    public static boolean isButton(String text) {
        return text != null && LABELS.contains(text.strip());
    }
}
