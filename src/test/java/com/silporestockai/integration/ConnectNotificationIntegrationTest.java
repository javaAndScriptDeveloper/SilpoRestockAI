package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.silporestockai.entity.User;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConnectNotificationService;
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

@DisplayName("a finished OAuth connect reaches the chat the person is actually waiting in")
class ConnectNotificationIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "606:stub-bot-token";
    private static final long CHAT_ID = 60601L;
    private static final StubTelegramServer TELEGRAM = startTelegram();

    @Autowired
    private ConnectNotificationService connectNotificationService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

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
        userRepository.deleteAll();
    }

    @Test
    void resolvesTheChatFromTheUserAndSends() {
        User user = userAccountService.findOrCreate(CHAT_ID);

        connectNotificationService.push(user.getId(), "✅ Акаунт «Сільпо» підключено.");

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
        assertThat(TELEGRAM.sentMessages().getFirst().path("chat_id").asLong()).isEqualTo(CHAT_ID);
        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText())
                .isEqualTo("✅ Акаунт «Сільпо» підключено.");
    }

    /**
     * The caller is a browser-facing controller: whatever goes wrong reaching Telegram, the person staring at the
     * landing page must still get their page, not a 500.
     */
    @Test
    void anUnknownUserIsSilentlyIgnoredRatherThanThrown() {
        assertThatCode(() -> connectNotificationService.push(UUID.randomUUID(), "не має кому"))
                .doesNotThrowAnyException();

        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }
}
