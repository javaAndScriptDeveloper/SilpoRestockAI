package com.silporestockai.model;

import java.util.List;

/**
 * The facts gathered before the model is asked anything (task 68): per person, and for the group as a whole.
 *
 * @param participants one entry per counted participant
 * @param sameGroupLastTime what a closely matching set of the same people agreed on last time; empty when no
 *     earlier round overlaps enough
 * @param sameGroupLastTimeTag that round's stated occasion, or null
 * @param seasonal what groups of a similar size agreed on around this date in earlier years; empty unless at
 *     least one participant needs it
 */
public record GroupSignals(
        List<ParticipantSignals> participants,
        List<HistoricalLine> sameGroupLastTime,
        String sameGroupLastTimeTag,
        List<HistoricalLine> seasonal) {}
