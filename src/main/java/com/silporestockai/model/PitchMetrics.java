package com.silporestockai.model;

import java.time.Duration;
import java.util.List;

/**
 * The numbers the pitch may quote (task 37), each next to the sample it came from.
 *
 * <p>Ratios are derived here rather than stored, so a reader of the record always sees numerator and denominator —
 * "75 %" on its own hides that it was three of four.
 *
 * @param onboardedHouseholds users with a saved profile
 * @param householdsWithConfirmedOrder users with at least one {@code CONFIRMED} order
 * @param medianOnboardingToFirstOrder median of (first confirmed order − user created), or null when nobody has one
 * @param fastestOnboardingToFirstOrder the shortest of those, or null
 * @param checkinPromptsSent check-in prompts the agent sent, summed over users
 * @param checkinResponses check-in answers received (text, voice or photo)
 * @param reorderConfirmations confirmed reorders with an edit flag recorded
 * @param reorderConfirmationsUnedited of those, confirmed as proposed
 * @param cartBuildsMeasured orders that recorded how many lines went unresolved
 * @param resolvedLines lines that made it into those carts, summed
 * @param unresolvedLines lines Silpo matched nothing for, summed
 * @param mcpCallsTotal MCP tool calls logged
 * @param mcpCallsFailed of those, answered with an error
 * @param mcpDistinctTools the distinct tool names, sorted
 */
public record PitchMetrics(
        int onboardedHouseholds,
        int householdsWithConfirmedOrder,
        Duration medianOnboardingToFirstOrder,
        Duration fastestOnboardingToFirstOrder,
        int checkinPromptsSent,
        int checkinResponses,
        int reorderConfirmations,
        int reorderConfirmationsUnedited,
        int cartBuildsMeasured,
        int resolvedLines,
        int unresolvedLines,
        int mcpCallsTotal,
        int mcpCallsFailed,
        List<String> mcpDistinctTools) {

    /** Answers per prompt, or null when nothing was ever asked. */
    public Double checkinResponseRate() {
        return checkinPromptsSent == 0 ? null : (double) checkinResponses / checkinPromptsSent;
    }

    public Double uneditedReorderShare() {
        return reorderConfirmations == 0 ? null : (double) reorderConfirmationsUnedited / reorderConfirmations;
    }

    /** Lines Silpo found, over lines asked for. */
    public Double resolveRate() {
        int asked = resolvedLines + unresolvedLines;
        return asked == 0 ? null : (double) resolvedLines / asked;
    }
}
