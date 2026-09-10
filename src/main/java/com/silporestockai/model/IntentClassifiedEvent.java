package com.silporestockai.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The router classified a message (task 55) — published for the evidence log, exactly as
 * {@link McpToolCalledEvent} is for MCP calls.
 *
 * <p>An event rather than a repository call so the router keeps knowing nothing about the pitch artifact: the
 * classification is a fact about the conversation, and who writes it down is not the router's business.
 *
 * @param intent the {@code IntentType} name when the message was routed, otherwise the outcome
 * @param outcome {@code ROUTED}, {@code UNCLASSIFIED} or {@code FAILED}
 * @param confidence the model's own confidence, or null when the classification call threw
 * @param userId whose message it was
 * @param classifiedAt when the message reached the router — before the model call, so the clock is honest
 */
public record IntentClassifiedEvent(
        String intent, String outcome, Double confidence, UUID userId, Instant classifiedAt) {}
