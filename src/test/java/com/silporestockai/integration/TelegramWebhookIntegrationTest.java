package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("POST /telegram/webhook routes updates and resumes conversation state between calls")
class TelegramWebhookIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "222:stub-bot-token";
    private static final String SECRET = "stub-webhook-secret";
    private static final long CHAT_ID = 9001L;
    private static final StubTelegramServer STUB = start();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

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
        registry.add("telegram.webhook-secret", () -> SECRET);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
        userProfileRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void deliver(String body) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private static String textUpdate(int updateId, String text) {
        return """
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "text":"%s"}}""".formatted(updateId, updateId, CHAT_ID, text);
    }

    private static String voiceUpdate(int updateId, String fileId, int duration) {
        return """
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "voice":{"file_id":"%s","file_unique_id":"u1","duration":%d,"mime_type":"audio/ogg"}}}""".formatted(updateId, updateId, CHAT_ID, fileId, duration);
    }

    private static String callbackUpdate(int updateId, String callbackId, String data) {
        return """
                {"update_id":%d,"callback_query":{"id":"%s","chat_instance":"ci",\
                "from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "data":"%s","message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}}}}""".formatted(updateId, callbackId, data, updateId, CHAT_ID);
    }

    @Test
    void aFirstTextMessageStartsOnboarding() throws Exception {
        deliver(textUpdate(1, "привіт"));

        assertThat(STUB.sentMessages()).hasSize(1);
        assertThat(STUB.sentMessages().getFirst().path("chat_id").asLong()).isEqualTo(CHAT_ID);
        assertThat(STUB.sentMessages().getFirst().path("text").asText()).contains("Комора");
        assertThat(conversationStateRepository.count()).isEqualTo(1);
    }

    @Test
    void aSecondMessageContinuesTheSameConversationRatherThanStartingANewOne() throws Exception {
        deliver(textUpdate(1, "привіт"));
        deliver(textUpdate(2, "ще раз"));

        assertThat(conversationStateRepository.count()).isEqualTo(1);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsAWrongSecretTokenWithoutRoutingAnything() throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textUpdate(1, "не має пройти")))
                .andExpect(status().isUnauthorized());

        assertThat(STUB.sentMessages()).isEmpty();
        assertThat(conversationStateRepository.count()).isZero();
    }

    @Test
    void answersTwoHundredAndSendsNothingForAnUpdateKindWeDoNotHandle() throws Exception {
        deliver("{\"update_id\":7,\"poll\":{\"id\":\"p1\",\"question\":\"?\",\"options\":[],"
                + "\"total_voter_count\":0,\"is_closed\":false,\"is_anonymous\":true,\"type\":\"regular\","
                + "\"allows_multiple_answers\":false}}");

        assertThat(STUB.sentMessages()).isEmpty();
        assertThat(conversationStateRepository.count()).isZero();
    }

    @Test
    void answersTwoHundredEvenWhenTheBodyIsNotAValidUpdate() throws Exception {
        deliver("{\"totally\":\"not an update\"}");

        assertThat(STUB.sentMessages()).isEmpty();
    }

    @Test
    void voiceNotesAreAcknowledgedRatherThanDropped() throws Exception {
        deliver(textUpdate(1, "привіт"));
        deliver(voiceUpdate(3, "voice-file-id", 7));

        assertThat(STUB.sentMessages().getLast().path("text").asText()).contains("Голосові");
    }

    @Test
    void routesAnInlineButtonCallbackAndAcknowledgesIt() throws Exception {
        deliver(textUpdate(1, "привіт"));

        deliver(callbackUpdate(4, "cb-1", "onb:skip"));

        assertThat(STUB.callbackAnswers()).hasSize(1);
        assertThat(STUB.callbackAnswers().getFirst().path("callback_query_id").asText())
                .isEqualTo("cb-1");
    }
}
