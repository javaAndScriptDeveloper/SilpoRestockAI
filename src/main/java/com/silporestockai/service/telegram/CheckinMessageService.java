package com.silporestockai.service.telegram;

import com.silporestockai.model.CheckinDelta;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The wording of a check-in, kept out of the service that decides when one happens.
 *
 * <p>Same split as {@code CartMessageService}: a copy change must never touch a class that writes to the database.
 */
@Service
public class CheckinMessageService {

    /**
     * Opens the conversation. Deliberately two short questions rather than a form: the answer is parsed by a model in
     * task 12, so free wording costs nothing, and a person answering a bot on a Tuesday evening will not fill in a
     * form.
     */
    public String promptText() {
        return "Як справи з їжею? Що вже закінчилось, а чого ще вистачає?";
    }

    /**
     * Asked when nothing could be pulled out of the answer.
     *
     * <p>Names a few real items rather than repeating the open question: "не зрозумів, повтори" gets the same
     * unparseable sentence back, while a question about specific products gets specific answers.
     */
    public String clarificationText(List<String> baselineItems) {
        List<String> examples = baselineItems.stream().limit(3).toList();
        if (examples.isEmpty()) {
            return "Не розібрав. Напиши коротко: чого вже нема, а чого ще вистачає?";
        }
        return "Не розібрав. Скажи коротко по цих: %s — що ще є, а що закінчилось?"
                .formatted(String.join(", ", examples));
    }

    /** Repeats back what was understood. A silent "ок" leaves the user unable to tell a good parse from a bad one. */
    public String acknowledgementText(CheckinDelta delta) {
        StringBuilder text = new StringBuilder("Записав.");
        append(text, "Ще є", delta.stillHave());
        append(text, "Закінчується", delta.runningLow());
        append(text, "Немає", delta.goneCompletely());
        return text.toString();
    }

    /** Same sentence onboarding uses, so a user who sends a voice note twice gets one consistent answer. */
    public String voiceUnsupportedText() {
        return "Голосові поки не розбираю. Напиши, будь ласка, текстом.";
    }

    private static void append(StringBuilder text, String label, List<String> items) {
        if (!items.isEmpty()) {
            text.append('\n')
                    .append(label)
                    .append(": ")
                    .append(String.join(", ", items))
                    .append('.');
        }
    }
}
