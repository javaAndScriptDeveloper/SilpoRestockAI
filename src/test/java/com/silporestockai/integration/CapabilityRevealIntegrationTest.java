package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CapabilityRevealService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("the capability reveal fires once and then never again")
class CapabilityRevealIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "555:stub-bot-token";
    private static final long CHAT_ID = 8301L;
    private static final StubTelegramServer TELEGRAM = startTelegram();

    @Autowired
    private CapabilityRevealService capabilityRevealService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User profiledUser() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .onlyUaProducer(false)
                .build());
        return user;
    }

    @Test
    void sendsTheTeaserAndStampsTheProfile() {
        User user = profiledUser();

        capabilityRevealService.revealOnce(user);

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText())
                .contains("Я розумію й звичайні прохання")
                .contains("«Світло вимкнули»")
                .contains("❓ Інструкція");
        assertThat(userProfileRepository
                        .findByUserId(user.getId())
                        .orElseThrow()
                        .getCapabilityRevealSentAt())
                .isNotNull();
    }

    /** Re-opening «Анкета» later runs the same plan hand-off. It must not re-teach what was already taught. */
    @Test
    void staysSilentOnEverySubsequentCall() {
        User user = profiledUser();

        capabilityRevealService.revealOnce(user);
        capabilityRevealService.revealOnce(user);
        capabilityRevealService.revealOnce(user);

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
    }

    /** No profile means onboarding never finished; there is nothing to stamp and nothing worth saying. */
    @Test
    void saysNothingWhenTheUserHasNoProfileYet() {
        User user = userAccountService.findOrCreate(CHAT_ID);

        capabilityRevealService.revealOnce(user);

        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }
}
