package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubRespeecherServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("the bot speaks only when a chat asked it to, and only what a person could say aloud")
class VoiceReplyIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "1111:stub-bot-token";
    private static final long CHAT_ID = 12001L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubAnthropicServer CLAUDE = startClaude();
    private static final StubRespeecherServer RESPEECHER = startRespeecher();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TelegramOutboundService telegramOutboundService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Claude stub", e);
        }
    }

    private static StubRespeecherServer startRespeecher() {
        try {
            return new StubRespeecherServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Respeecher stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("claude.api-key", () -> "stub-anthropic-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
        registry.add("respeecher.api-key", () -> "stub-respeecher-key");
        registry.add("respeecher.base-url", RESPEECHER::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        CLAUDE.close();
        RESPEECHER.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        CLAUDE.reset();
        RESPEECHER.reset();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();

        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
        // Whatever the bot says, the rewrite comes back as something a person could actually say.
        CLAUDE.respondWithText("Кошик готовий. Дві позиції, разом сімдесят три гривні.");
    }

    private void sendText(int updateId, String text) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "text":"%s"}}""".formatted(updateId, updateId, CHAT_ID, text)))
                .andExpect(status().isOk());
    }

    private boolean voiceEnabled() {
        return userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().isVoiceRepliesEnabled();
    }

    @Test
    void voiceIsOffUntilSomebodyAsksForIt() {
        telegramOutboundService.sendMessage(CHAT_ID, "Записав.");

        assertThat(voiceEnabled()).isFalse();
        assertThat(RESPEECHER.spokenTranscripts()).isEmpty();
        assertThat(TELEGRAM.sentAudio()).isEmpty();
    }

    @Test
    void theVoiceCommandTurnsItOnAndTheAnswerItselfIsSpoken() throws Exception {
        sendText(1, "/voice");

        assertThat(voiceEnabled()).isTrue();
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("голосом");
        // The confirmation is the first thing spoken, which is also the fastest way to hear that it works.
        assertThat(TELEGRAM.sentAudio()).hasSize(1);
    }

    @Test
    void whatIsSpokenIsTheRewrittenTextNotTheRawMessage() {
        userRepository.save(withVoiceOn());

        telegramOutboundService.sendMessage(CHAT_ID, "Разом: 73.50 грн\nОплата: https://silpo.ua/checkout/cart-1");

        assertThat(RESPEECHER.spokenTranscripts())
                .containsExactly("Кошик готовий. Дві позиції, разом сімдесят три гривні.");
        // The style guide is what was asked for, and the raw message is what was asked about.
        JsonNode rewriteRequest = CLAUDE.requests().getLast();
        assertThat(rewriteRequest.toString()).contains("NEVER READ ALOUD").contains("73.50");
        // This call runs on every outbound message once voice is on; it must not be priced like a meal plan.
        assertThat(rewriteRequest.path("model").asText()).isEqualTo("claude-haiku-4-5-20251001");
    }

    /**
     * The pattern behind a real incident: a two-minute check-in loop with voice left on turned trivial one-line
     * confirmations into the majority of a day's Claude calls, each paying the full style-guide system prompt for a
     * handful of words back. A message that is already speakable must not reach Claude at all.
     */
    @Test
    void aTrivialConfirmationIsSpokenDirectlyWithoutCallingClaude() {
        userRepository.save(withVoiceOn());

        telegramOutboundService.sendMessage(CHAT_ID, "Записав.");

        assertThat(CLAUDE.requests()).isEmpty();
        assertThat(RESPEECHER.spokenTranscripts()).containsExactly("Записав.");
    }

    @Test
    void theAudioTelegramReceivesIsWhatRespeecherReturned() {
        userRepository.save(withVoiceOn());

        telegramOutboundService.sendMessage(CHAT_ID, "Записав.");

        assertThat(TELEGRAM.sentAudio()).singleElement().asString().contains("stub-wav-audio-payload");
        assertThat(RESPEECHER.apiKeys()).containsExactly("stub-respeecher-key");
        // The Ukrainian model, which is the one that understands stress marks.
        assertThat(RESPEECHER.paths()).containsExactly("/v1/public/tts/ua-rt/tts/bytes");
    }

    @Test
    void aMessageWithButtonsStaysWritten() {
        userRepository.save(withVoiceOn());

        telegramOutboundService.sendMessageWithButtons(
                CHAT_ID, "Підтвердити цей кошик?", List.of(TelegramButton.callback("Так", "yes")));

        // Buttons are a thing you tap; reading a cart aloud two items at a time is worse than not speaking.
        assertThat(TELEGRAM.sentAudio()).isEmpty();
        assertThat(RESPEECHER.spokenTranscripts()).isEmpty();
    }

    @Test
    void aRefusedSynthesisStillDeliversTheWrittenMessage() {
        userRepository.save(withVoiceOn());
        RESPEECHER.respondWithStatus(500);

        telegramOutboundService.sendMessage(CHAT_ID, "Записав.");

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).isEqualTo("Записав.");
        assertThat(TELEGRAM.sentAudio()).isEmpty();
    }

    @Test
    void theCommandTurnsItOffAgain() throws Exception {
        sendText(1, "/voice");
        sendText(2, "/voice");

        assertThat(voiceEnabled()).isFalse();
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Вимкнув");
    }

    private User withVoiceOn() {
        User stored = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow();
        stored.setVoiceRepliesEnabled(true);
        return stored;
    }
}
