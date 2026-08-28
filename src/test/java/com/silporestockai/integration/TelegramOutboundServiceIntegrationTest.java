package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.TelegramButton;
import com.silporestockai.service.telegram.TelegramOutboundService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("TelegramOutboundService talks to the Bot API and is the only class that does")
class TelegramOutboundServiceIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "111:stub-bot-token";
    private static final StubTelegramServer STUB = start();

    @Autowired
    private TelegramOutboundService telegramOutboundService;

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
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
    }

    @Test
    void sendsAPlainMessage() {
        telegramOutboundService.sendMessage(777L, "Комора: привіт");

        assertThat(STUB.sentMessages()).hasSize(1);
        assertThat(STUB.sentMessages().getFirst().path("chat_id").asLong()).isEqualTo(777L);
        assertThat(STUB.sentMessages().getFirst().path("text").asText()).isEqualTo("Комора: привіт");
    }

    @Test
    void sendsInlineButtonsAsASingleRow() {
        telegramOutboundService.sendMessageWithButtons(
                777L,
                "Підтвердити кошик?",
                List.of(new TelegramButton("Так", "cart:confirm"), new TelegramButton("Ні", "cart:cancel")));

        var keyboard = STUB.sentMessages().getFirst().path("reply_markup").path("inline_keyboard");
        assertThat(keyboard).hasSize(1);
        assertThat(keyboard.get(0)).hasSize(2);
        assertThat(keyboard.get(0).get(0).path("text").asText()).isEqualTo("Так");
        assertThat(keyboard.get(0).get(0).path("callback_data").asText()).isEqualTo("cart:confirm");
        assertThat(keyboard.get(0).get(1).path("callback_data").asText()).isEqualTo("cart:cancel");
    }

    @Test
    void answersACallbackQuery() {
        telegramOutboundService.answerCallback("callback-1");

        assertThat(STUB.callbackAnswers()).hasSize(1);
        assertThat(STUB.callbackAnswers().getFirst().path("callback_query_id").asText())
                .isEqualTo("callback-1");
    }

    @Test
    void downloadsAVoiceNoteAsRawBytes() {
        byte[] audio = telegramOutboundService.downloadVoiceNote("voice-file-id");

        assertThat(audio).isEqualTo(StubTelegramServer.VOICE_BYTES);
    }
}
