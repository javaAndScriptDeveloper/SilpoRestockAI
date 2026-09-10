package com.silporestockai.service;

import com.silporestockai.entity.User;
import com.silporestockai.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the person behind a Telegram chat, creating the row on first contact.
 *
 * <p>Onboarding needs a user id before it can build the Silpo authorisation URL, so the row exists from the very first
 * message rather than from the end of the conversation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final UserRepository userRepository;
    private final ObservabilityService observabilityService;

    @Transactional
    public User findOrCreate(long telegramChatId) {
        return findOrCreate(telegramChatId, null);
    }

    /**
     * The same lookup, told what Telegram called this person on the update that arrived (task 81).
     *
     * <p>A missing username never erases a stored one: Telegram omits it from some update shapes, and a gift
     * addressed to «@olena» must not stop resolving because she happened to tap a button last.
     */
    @Transactional
    public User findOrCreate(long telegramChatId, String telegramUsername) {
        User user = userRepository.findByTelegramChatId(telegramChatId).orElseGet(() -> {
            User created = userRepository.save(User.builder()
                    .id(UUID.randomUUID())
                    .telegramChatId(telegramChatId)
                    .createdAt(Instant.now())
                    .build());
            log.info("registered a new user for chat {}", telegramChatId);
            // Only on the insert branch: this is the top of the funnel, not a count of messages.
            observabilityService.recordOnboardingStarted();
            return created;
        });
        if (telegramUsername != null
                && !telegramUsername.isBlank()
                && !telegramUsername.strip().equals(user.getTelegramUsername())) {
            user.setTelegramUsername(telegramUsername.strip());
            userRepository.save(user);
        }
        return user;
    }
}
