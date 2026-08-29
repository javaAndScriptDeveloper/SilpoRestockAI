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

    @Transactional
    public User findOrCreate(long telegramChatId) {
        return userRepository.findByTelegramChatId(telegramChatId).orElseGet(() -> {
            User created = userRepository.save(User.builder()
                    .id(UUID.randomUUID())
                    .telegramChatId(telegramChatId)
                    .createdAt(Instant.now())
                    .build());
            log.info("registered a new user for chat {}", telegramChatId);
            return created;
        });
    }
}
