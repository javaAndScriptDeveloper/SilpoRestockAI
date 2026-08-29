package com.silporestockai.model;

import java.util.List;

/**
 * What could be learned about a household from their Silpo account, before anyone was asked a question.
 *
 * <p>Every field is nullable: the four MCP tools may each answer, refuse, or return nothing, and a guest with no order
 * history yields an entirely empty snapshot. The onboarding flow asks only about the fields that came back null.
 *
 * @param householdSize how many people eat at home
 * @param hasKids whether there are children in the household
 * @param kidsAges their ages, when known
 * @param dietaryRestrictions allergies and diet restrictions
 * @param frequentItems items the guest buys often, used later to seed the first plan
 */
public record SilpoProfileSnapshot(
        Integer householdSize,
        Boolean hasKids,
        List<Integer> kidsAges,
        List<String> dietaryRestrictions,
        List<String> frequentItems) {

    public static SilpoProfileSnapshot empty() {
        return new SilpoProfileSnapshot(null, null, null, null, null);
    }

    /** True when nothing at all was learned, which is the same path as a guest who never connected. */
    public boolean isEmpty() {
        return householdSize == null
                && hasKids == null
                && (kidsAges == null || kidsAges.isEmpty())
                && (dietaryRestrictions == null || dietaryRestrictions.isEmpty())
                && (frequentItems == null || frequentItems.isEmpty());
    }
}
