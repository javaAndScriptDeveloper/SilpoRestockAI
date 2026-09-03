package com.silporestockai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Telegram bot.
 *
 * <p>Holds no Telegram SDK types on purpose: an ArchUnit rule keeps those inside {@code controller.telegram} and
 * {@code service.telegram}.
 *
 * @param botToken bot token from &#64;BotFather; blank in tests and CI
 * @param webhookUrl public HTTPS URL of the webhook; blank skips registration at startup
 * @param webhookSecret shared secret Telegram echoes in {@code X-Telegram-Bot-Api-Secret-Token}; blank disables the
 *     check, which is acceptable only for local work
 * @param apiUrl Bot API base URL; overridden by tests to reach a local stub
 * @param webAppBaseUrl public HTTPS base URL Spring serves static resources from, used to build the onboarding
 *     WebApp form's URL; blank skips the WebApp button and onboarding falls back to the text chain
 */
@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(
        String botToken, String webhookUrl, String webhookSecret, String apiUrl, String webAppBaseUrl) {

    public boolean webhookSecretConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    public boolean webhookUrlConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /** True when a WebApp base URL is configured — onboarding falls back straight to the text chain when not. */
    public boolean webAppConfigured() {
        return webAppBaseUrl != null && !webAppBaseUrl.isBlank();
    }
}
