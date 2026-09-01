package com.silporestockai.model;

import java.util.List;

/**
 * An item that did not make it into the cart, and what Silpo offers instead.
 *
 * <p>Kept next to the original name rather than silently swapped: substituting someone's bread for a different bread
 * without asking is the kind of helpfulness the product brief warns against. Task 15 puts the choice to the user.
 *
 * @param requestedName the name that was asked for
 * @param options what Silpo suggested; empty when it had nothing, which is still worth telling the user
 */
public record ReplacementSuggestion(String requestedName, List<ReplacementOption> options) {}
