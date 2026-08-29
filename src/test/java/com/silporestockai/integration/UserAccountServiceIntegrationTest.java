package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("a Telegram chat maps to exactly one user row")
class UserAccountServiceIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 7101L;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    @Test
    void createsTheUserOnFirstContact() {
        User created = userAccountService.findOrCreate(CHAT_ID);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getTelegramChatId()).isEqualTo(CHAT_ID);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void returnsTheSameUserOnEveryLaterMessage() {
        User first = userAccountService.findOrCreate(CHAT_ID);
        User second = userAccountService.findOrCreate(CHAT_ID);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.count()).isEqualTo(1);
    }
}
