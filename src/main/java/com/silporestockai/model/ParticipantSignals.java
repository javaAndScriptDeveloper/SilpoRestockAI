package com.silporestockai.model;

import java.util.List;

/**
 * Everything the algorithmic pass knows about one counted participant (task 68), in the order the model must
 * weigh it: the reply given now, then what this person drank in other rounds.
 *
 * @param rawReply the reply as typed, «.» for «на розсуд бота»
 * @param personalHistory durable preferences from this person's other rounds, newest first — never the raw
 *     sentence of a past round, so a one-evening exception does not travel
 * @param needsSeasonal true when the person gave no preference and nothing is known about them from any round —
 *     the only case the seasonal average is shown for
 */
public record ParticipantSignals(
        long telegramUserId,
        String displayName,
        String rawReply,
        List<String> personalHistory,
        boolean needsSeasonal) {}
