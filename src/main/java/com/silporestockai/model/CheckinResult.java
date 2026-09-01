package com.silporestockai.model;

/**
 * What one check-in message turned into.
 *
 * @param delta the three buckets, already filtered to real baseline items; empty in every bucket when nothing could
 *     be understood
 * @param rawText what was parsed — the user's own words, or the transcript of their voice note
 * @param needsClarification true when the answer has to be asked about again. An empty delta must never be recorded
 *     as "everything unchanged": that is the one wrong answer that silently corrupts the next reorder.
 */
public record CheckinResult(CheckinDelta delta, String rawText, boolean needsClarification) {}
