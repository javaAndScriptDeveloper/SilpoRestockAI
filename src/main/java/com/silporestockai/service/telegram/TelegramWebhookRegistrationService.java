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
        } catch (RuntimeException e) {
            // Deliberately not fatal: a Telegram outage at boot must not stop the app from serving, and a
            // previously registered webhook keeps delivering. Re-register by restarting or by calling setWebhook.
            log.error("could not register the Telegram webhook at {}", properties.webhookUrl(), e);
        }
    }
}
