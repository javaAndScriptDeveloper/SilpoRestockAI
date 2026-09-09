package com.silporestockai.service;

import com.silporestockai.model.TelegramButton;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Says out loud, in the chat, that a browser-side OAuth connect finished.
 *
 * <p>The OAuth callbacks land in a browser tab, and Telegram sends no callback for a URL button — so without this the
 * conversation stays exactly where the person left it and they have to guess whether it worked. The chat id is not in
 * the OAuth state: both login states carry a {@code userId}, and {@code User.telegramChatId} is the chat.
 *
 * <p>Every failure is swallowed. The caller is answering a browser; a Telegram outage must cost the person their
 * confirmation message, never their landing page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectNotificationService {

    private final UserRepository userRepository;
    private final TelegramOutboundService telegramOutboundService;

    /** Best-effort. Returns normally whether the message was sent, skipped or failed. */
    public void push(UUID userId, String text) {
        push(userId, text, List.of());
    }

    /**
     * The same, with buttons — a failed connect sends a fresh «Під'єднати Сільпо» this way, because the button that
     * produced the failure is still sitting in the chat and would fail identically on the next tap.
     */
    public void push(UUID userId, String text, List<TelegramButton> buttons) {
        try {
            userRepository
                    .findById(userId)
                    .ifPresentOrElse(
                            user -> {
                                if (buttons.isEmpty()) {
                                    telegramOutboundService.sendMessage(user.getTelegramChatId(), text);
                                } else {
                                    telegramOutboundService.sendMessageWithButtons(
                                            user.getTelegramChatId(), text, buttons);
                                }
                            },
                            () -> log.warn("no user {} to notify about an OAuth connect", userId));
        } catch (RuntimeException e) {
            log.warn("could not push the OAuth connect confirmation to user {}", userId, e);
        }
    }
}
