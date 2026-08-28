package com.silporestockai.model;

import java.util.List;

/**
 * A check-in reduced to three buckets, as stored in {@code checkin.parsed_delta_json}.
 *
 * <p>Item names come from the user's current baseline: task 12 puts the baseline in the prompt so the model maps loose
 * phrasing onto real items rather than inventing new ones.
 *
 * @param stillHave items the user reports having enough of
 * @param runningLow items about to run out
 * @param goneCompletely items already gone
 */
public record CheckinDelta(List<String> stillHave, List<String> runningLow, List<String> goneCompletely) {}
