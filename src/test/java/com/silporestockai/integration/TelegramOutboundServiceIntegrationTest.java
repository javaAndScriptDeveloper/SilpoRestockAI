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
                List.of(TelegramButton.callback("Так", "cart:confirm"), TelegramButton.callback("Ні", "cart:cancel")));

        var keyboard = STUB.sentMessages().getFirst().path("reply_markup").path("inline_keyboard");
        assertThat(keyboard).hasSize(1);
        assertThat(keyboard.get(0)).hasSize(2);
        assertThat(keyboard.get(0).get(0).path("text").asText()).isEqualTo("Так");
        assertThat(keyboard.get(0).get(0).path("callback_data").asText()).isEqualTo("cart:confirm");
        assertThat(keyboard.get(0).get(1).path("callback_data").asText()).isEqualTo("cart:cancel");
    }

    @Test
    void wrapsFourShortInlineButtonsIntoTwoRows() {
        telegramOutboundService.sendMessageWithButtons(
                777L,
                "Ось що пропоную взяти:",
                List.of(
                        TelegramButton.callback("Замовити", "list:order"),
                        TelegramButton.callback("Змінити", "list:edit"),
                        TelegramButton.callback("Змінити вручну", "list:manual"),
                        TelegramButton.callback("Скасувати", "list:cancel")));

        var keyboard = STUB.sentMessages().getFirst().path("reply_markup").path("inline_keyboard");
        assertThat(keyboard).hasSize(2);
        assertThat(keyboard.get(0)).hasSize(2);
        assertThat(keyboard.get(1)).hasSize(2);
        assertThat(keyboard.get(1).get(0).path("text").asText()).isEqualTo("Змінити вручну");
    }

    @Test
    void givesEveryLongInlineLabelItsOwnRow() {
        // Three cooking-time options: the longest is 32 characters and would be cut to «…» beside another one.
        telegramOutboundService.sendMessageWithButtons(
                777L,
                "Спершу головне: як у тебе з готуванням?",
                List.of(
                        TelegramButton.callback("Готую потроху щодня", "onb:cook:DAILY"),
                        TelegramButton.callback("Готую наперед, раз на кілька днів", "onb:cook:BATCH"),
                        TelegramButton.callback("Не готую — лише готова їжа", "onb:cook:READY")));

        var keyboard = STUB.sentMessages().getFirst().path("reply_markup").path("inline_keyboard");
        assertThat(keyboard).hasSize(3);
        assertThat(keyboard.get(0)).hasSize(1);
        assertThat(keyboard.get(2).get(0).path("text").asText()).isEqualTo("Не готую — лише готова їжа");
    }

    @Test
    void sendsThePersistentMainMenuKeyboard() {
        telegramOutboundService.sendMessageWithMainMenu(777L, "Записав. Готую перший план на тиждень.");

        // Task 31 retired the button-per-feature menu in favour of chat-first free text; tasks 33, 47 and 57
        // added back the three things that are management views rather than requests. Never more than two per
        // row, so no label gets truncated on a phone — see MainMenuKeyboard's own javadoc.
        var keyboard = STUB.sentMessages().getFirst().path("reply_markup").path("keyboard");
        assertThat(keyboard).hasSize(3);
        assertThat(keyboard.get(0)).hasSize(2);
        assertThat(keyboard.get(1)).hasSize(2);
        assertThat(keyboard.get(2)).hasSize(2);
        assertThat(keyboard.get(0).get(0).path("text").asText()).isEqualTo("📝 Список");
        // Task 57: order status is state a person re-checks, so it sits next to the list rather than in chat.
        assertThat(keyboard.get(0).get(1).path("text").asText()).isEqualTo("📦 Замовлення");
        assertThat(keyboard.get(1).get(0).path("text").asText()).isEqualTo("🗓 Заплановані");
        assertThat(keyboard.get(1).get(1).path("text").asText()).isEqualTo("🧾 Анкета");
        assertThat(keyboard.get(2).get(0).path("text").asText()).isEqualTo("❓ Інструкція");
        // The two buttons that are about the bot rather than about groceries share the last row.
        assertThat(keyboard.get(2).get(1).path("text").asText()).isEqualTo("💬 Фідбек");
        assertThat(STUB.sentMessages()
                        .getFirst()
                        .path("reply_markup")
                        .path("resize_keyboard")
                        .asBoolean())
                .isTrue();
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
        byte[] audio = telegramOutboundService.downloadFile("voice-file-id");

        assertThat(audio).isEqualTo(StubTelegramServer.VOICE_BYTES);
    }

    @Test
    void sendsAUrlButtonWhenTheButtonCarriesALink() {
        telegramOutboundService.sendMessageWithButtons(
                777L,
                "Під'єднай Сільпо",
                List.of(
                        TelegramButton.link("Під'єднати Сільпо", "https://mcp.silpo.ua/authorize?x=1"),
                        TelegramButton.callback("Пропустити", "onb:skip")));

        var row = STUB.sentMessages()
                .getFirst()
                .path("reply_markup")
                .path("inline_keyboard")
                .get(0);
        assertThat(row.get(0).path("url").asText()).isEqualTo("https://mcp.silpo.ua/authorize?x=1");
        assertThat(row.get(0).has("callback_data")).isFalse();
        assertThat(row.get(1).path("callback_data").asText()).isEqualTo("onb:skip");
        assertThat(row.get(1).has("url")).isFalse();
    }
}
