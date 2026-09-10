package com.silporestockai.utils;

import java.util.List;
import java.util.Locale;

/**
 * Whether a group reply is asking for something to eat.
 *
 * <p>The group round resolves drinks and only drinks — task 68's own decision, and not an oversight: what a
 * company may eat is an allergy question, and a group chat is not a place where that consent can be taken.
 * Task 74 keeps the copy from pretending otherwise, and the copy that matters most is the sentence said to the
 * person who just typed «чіпси», in the moment they typed it, rather than a scope note nobody reads.
 *
 * <p>Deterministic and cheap on purpose: this runs on every reply, and a model call to decide whether «пиво» is
 * food would be a second of latency for an answer a word already gives. It is not the only guard — the proposal
 * prompt says the same thing for whatever these words miss — so it is allowed to be incomplete, never wrong.
 */
public final class GroupOrderScope {

    private static final List<String> EATEN_NOT_DRUNK = List.of(
            "чіпс", "закус", "поїсти", "їсти", "їжа", "їжу", "шашлик", "мангал", "піц", "торт", "суші", "бургер", "сир",
            "ковбас", "м'яс", "мяс", "хліб", "салат", "снек", "горішк", "сухарик", "цукерк", "печив", "фрукт");

    private GroupOrderScope() {}

    /** True when the reply names something to eat. See the class note on why it may be incomplete. */
    public static boolean asksForFood(String reply) {
        String text = reply == null ? "" : reply.toLowerCase(Locale.ROOT);
        return EATEN_NOT_DRUNK.stream().anyMatch(text::contains);
    }
}
