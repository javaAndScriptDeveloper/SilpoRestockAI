package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("a person's Telegram username, so a friend can be named by it")
class GiftUsernameIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 9101L;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    @Test
    void recordsTheUsernameOnFirstContactAndFindsItCaseInsensitively() {
        userAccountService.findOrCreate(CHAT_ID, "Olena");

        assertThat(userRepository.findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc("olena"))
                .map(User::getTelegramChatId)
                .contains(CHAT_ID);
    }

    @Test
    void followsARename() {
        userAccountService.findOrCreate(CHAT_ID, "olena");
        userAccountService.findOrCreate(CHAT_ID, "olena_k");

        assertThat(userRepository.findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc("olena"))
                .isEmpty();
        assertThat(userRepository.findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc("olena_k"))
                .isPresent();
    }

    @Test
    void keepsTheStoredNameWhenAnUpdateCarriesNone() {
        userAccountService.findOrCreate(CHAT_ID, "olena");
        userAccountService.findOrCreate(CHAT_ID, null);

        assertThat(userRepository.findFirstByTelegramUsernameIgnoreCaseOrderByCreatedAtDesc("olena"))
                .isPresent();
    }
}
