package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("the webhook registers itself at startup when a public URL is configured")
class TelegramWebhookRegistrationIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "333:stub-bot-token";
    private static final String WEBHOOK_URL = "https://komora.example/telegram/webhook";
    private static final String SECRET = "startup-secret";
    private static final StubTelegramServer STUB = start();

    private static StubTelegramServer start() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @DynamicPropertySource
    static void telegramProperties(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", STUB::baseUrl);
        registry.add("telegram.webhook-url", () -> WEBHOOK_URL);
        registry.add("telegram.webhook-secret", () -> SECRET);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Test
    void callsSetWebhookOnceWithTheConfiguredUrlAndSecret() {
        assertThat(STUB.setWebhookCalls()).hasSize(1);
        assertThat(STUB.setWebhookCalls().getFirst().path("url").asText()).isEqualTo(WEBHOOK_URL);
        assertThat(STUB.setWebhookCalls().getFirst().path("secret_token").asText())
                .isEqualTo(SECRET);
    }
}
