package com.silporestockai.controller.telegram;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.silporestockai.config.TelegramProperties;
import com.silporestockai.service.telegram.TelegramRoutingService;
import io.swagger.v3.oas.annotations.Operation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * The single endpoint Telegram delivers updates to.
 *
 * <p>The body is taken as a raw string and parsed here rather than by Spring's message converter. Spring Boot 4
 * carries both Jackson 2 and Jackson 3 on the classpath and may bind with either; every {@code telegrambots-meta} type
 * is annotated for Jackson 2. Parsing with a mapper this class owns removes that coupling, and lets unknown fields be
 * ignored — Telegram adds fields to {@code Update} continuously.
 *
 * <p>Except for a failed secret-token check the endpoint always answers {@code 200}. Telegram retries any non-2xx
 * response indefinitely, so a single update the router cannot handle would otherwise loop forever.
 *
 * <p>Telegram also retries a slow one — {@code route} runs off this thread precisely so that never happens, but a
 * redelivery caused by anything else (a network blip on either side, this process restarting mid-update) is still
 * possible, and {@code TelegramRoutingService} has no way to tell a genuine second message from a duplicate of the
 * first. {@code update_id} is unique and strictly increasing per bot, so a small bounded memory of recently seen
 * ones is what turns a redelivered update into a no-op instead of reprocessing it — a fridge photo reprocessed is a
 * second full vision call for the same picture, silently. Per-process rather than persisted: Telegram's own retries
 * happen close together, not across a restart, and {@code conversation_state} is this application's only durable
 * memory for everything else.
 */
@Slf4j
@RestController
@RequestMapping("/telegram")
public class TelegramWebhookController {

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final ObjectMapper updateMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final TelegramProperties properties;
    private final TelegramRoutingService telegramRoutingService;
    private final Cache<Integer, Boolean> recentlySeenUpdateIds;

    public TelegramWebhookController(
            TelegramProperties properties,
            TelegramRoutingService telegramRoutingService,
            Cache<Integer, Boolean> telegramUpdateDedupCache) {
        this.properties = properties;
        this.telegramRoutingService = telegramRoutingService;
        this.recentlySeenUpdateIds = telegramUpdateDedupCache;
    }

    @Operation(summary = "Telegram webhook", description = "Receives Bot API updates. Called only by Telegram.")
    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(
            @RequestHeader(name = SECRET_HEADER, required = false) String secretToken, @RequestBody String body) {
        if (!secretAccepted(secretToken)) {
            log.warn("rejected a Telegram webhook call with a missing or wrong secret token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            Update update = updateMapper.readValue(body, Update.class);
            if (recentlySeenUpdateIds.asMap().putIfAbsent(update.getUpdateId(), Boolean.TRUE) != null) {
                log.debug("ignoring a redelivered Telegram update {}", update.getUpdateId());
                return ResponseEntity.ok().build();
            }
            telegramRoutingService.route(update);
        } catch (Exception e) {
            // Never propagate: Telegram retries a non-2xx forever, so one bad update would loop.
            log.error("failed to handle a Telegram update", e);
        }
        return ResponseEntity.ok().build();
    }

    private boolean secretAccepted(String provided) {
        if (!properties.webhookSecretConfigured()) {
            return true;
        }
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                properties.webhookSecret().getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
