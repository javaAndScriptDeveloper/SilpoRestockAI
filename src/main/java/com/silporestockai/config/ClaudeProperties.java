package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Anthropic Claude API.
 *
 * @param apiKey Anthropic API key; blank in tests and CI, which makes every call fail with a clear message rather than
 *     stopping the application from booting
 * @param model model id for calls that need real reasoning — meal planning, check-in parsing, the shopping list
 *     builder. All of them exist because a cheaper model got a nuanced Ukrainian instruction wrong (the bananas and
 *     invented-items bugs), so this stays a flagship-tier model.
 * @param fastModel model id for calls that do not: today, only the voice-style rewrite, which turns an existing
 *     sentence into a spoken one and needs no judgement calls the way meal planning or list building do. Defaults to
 *     Haiku, priced for exactly this — a call on every outbound message once a chat turns voice on must not cost
 *     what a meal plan costs.
 * @param maxTokens output token ceiling for a single call
 * @param timeout per-request timeout; high because meal plan generation is a long call
 * @param baseUrl API base URL; overridden by tests to reach a local stub
 * @param workspaceId workspace an identity-linked key acts in, sent as {@code anthropic-workspace-id}. Blank for an
 *     ordinary workspace key, which carries its workspace in the key itself; an identity-linked key is rejected
 *     without it.
 */
@ConfigurationProperties(prefix = "claude")
public record ClaudeProperties(
        String apiKey,
        String model,
        String fastModel,
        long maxTokens,
        Duration timeout,
        String baseUrl,
        String workspaceId) {

    public boolean workspaceIdConfigured() {
        return workspaceId != null && !workspaceId.isBlank();
    }

    public boolean apiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
