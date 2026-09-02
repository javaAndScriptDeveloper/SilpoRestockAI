package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Anthropic Claude API.
 *
 * @param apiKey Anthropic API key; blank in tests and CI, which makes every call fail with a clear message rather than
 *     stopping the application from booting
 * @param model model id, e.g. {@code claude-sonnet-5}
 * @param maxTokens output token ceiling for a single call
 * @param timeout per-request timeout; high because meal plan generation is a long call
 * @param baseUrl API base URL; overridden by tests to reach a local stub
 * @param workspaceId workspace an identity-linked key acts in, sent as {@code anthropic-workspace-id}. Blank for an
 *     ordinary workspace key, which carries its workspace in the key itself; an identity-linked key is rejected
 *     without it.
 */
@ConfigurationProperties(prefix = "claude")
public record ClaudeProperties(
        String apiKey, String model, long maxTokens, Duration timeout, String baseUrl, String workspaceId) {

    public boolean workspaceIdConfigured() {
        return workspaceId != null && !workspaceId.isBlank();
    }

    public boolean apiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
