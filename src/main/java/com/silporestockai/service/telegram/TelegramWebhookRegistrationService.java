package com.silporestockai.service.telegram;

import com.silporestockai.config.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Registers the webhook URL with Telegram once the application is up.
 *
 * <p>Gated on {@code telegram.webhook-url} being set, so tests, CI and a bare {@code make run} never call the Telegram
 * API. The URL rotates every time an ngrok tunnel restarts, which is why this is automatic rather than a one-off
 * manual step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramWebhookRegistrationService {

    private final TelegramProperties properties;
    private final TelegramOutboundService telegramOutboundService;

    @EventListener(ApplicationReadyEvent.class)
    public void registerWebhook() {
        if (!properties.webhookUrlConfigured()) {
            log.info("telegram.webhook-url is not set — skipping webhook registration");
            return;
        }
        try {
            telegramOutboundService.setWebhook(properties.webhookUrl(), properties.webhookSecret());
            log.info("registered the Telegram webhook at {}", properties.webhookUrl());
            if (!telegramOutboundService.canReadAllGroupMessages()) {
                // The group round (task 68) opens on «@bot збери напої…», and privacy mode never delivers that
                // sentence. Said once at boot so the recording does not find out in the group.
                log.warn("privacy mode is on for this bot: a plain @mention in a group never reaches it, so a "
                        + "group round opens only where the bot is an administrator — disable privacy mode in "
                        + "BotFather (/setprivacy) for the production bot");
            }
        } catch (RuntimeException e) {
            // Deliberately not fatal: a Telegram outage at boot must not stop the app from serving, and a
            // previously registered webhook keeps delivering. Re-register by restarting or by calling setWebhook.
            log.error("could not register the Telegram webhook at {}", properties.webhookUrl(), e);
        }
    }
}
