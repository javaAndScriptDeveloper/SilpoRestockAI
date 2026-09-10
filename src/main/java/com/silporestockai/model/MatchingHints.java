package com.silporestockai.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the product choice knows about a cart beyond the lines themselves (task 72).
 *
 * <p>Two things, and both exist because a need is not the same as a word for it:
 *
 * <ul>
 *   <li>{@code personsWords} — the sentence that asked for this cart. A line matched to the cheapest suitable
 *       candidate by default has to yield to a brand the person named themselves, and a fixed line («вода
 *       мінеральна» in the hangover kit) carries no brand of its own to yield with.
 *   <li>{@code alsoSearch} — the other shelf names one line's need goes by, searched in the <em>first</em> pass
 *       rather than only as the rescue pass for a line that found nothing. Silpo is a grocery: it has no ₴30
 *       charcoal tablets, only a ₴464 imported supplement called «Активоване вугілля», while Атоксіл sits on the
 *       same shelf at ₴119. A line bound to one name buys that name at whatever it costs; a line searched under
 *       every name of its need buys the cheapest thing that actually serves it.
 * </ul>
 */
public record MatchingHints(String personsWords, Map<String, List<String>> alsoSearch) {

    /** Nothing known: a weekly plan, a baseline reorder — every line is exactly its own name. */
    public static final MatchingHints NONE = new MatchingHints(null, Map.of());

    public MatchingHints {
        alsoSearch = alsoSearch == null ? Map.of() : Map.copyOf(alsoSearch);
    }

    /** Only the person's own sentence, for a cart whose lines already say what to search for. */
    public static MatchingHints ofWords(String personsWords) {
        return new MatchingHints(personsWords, Map.of());
    }

    /** The other names to search this line's need under, or empty — never null. */
    public List<String> alsoSearchFor(String lineName) {
        return lineName == null ? List.of() : alsoSearch.getOrDefault(lineName.toLowerCase(Locale.ROOT), List.of());
    }
}
