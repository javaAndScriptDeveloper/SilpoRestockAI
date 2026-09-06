package com.silporestockai.model;

import java.util.List;

/**
 * Claude's answer to "what else might this product be called on a shelf": alternative catalog search phrases for
 * shopping list lines whose first search found nothing usable.
 *
 * @param suggestions one entry per line the model had an idea for
 */
public record SearchTermSuggestions(List<Suggestion> suggestions) {

    /**
     * @param lineIndex the line as numbered in the request
     * @param terms up to a couple of short phrases to try instead
     */
    public record Suggestion(int lineIndex, List<String> terms) {}
}
