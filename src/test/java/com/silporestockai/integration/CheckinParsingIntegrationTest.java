package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CheckinDelta;
import com.silporestockai.model.CheckinResult;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.InventoryTrendRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CheckinParsingService;
import com.silporestockai.service.CheckinPromptService;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubSttServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

@DisplayName("a check-in answer becomes three lists of real baseline items, typed or spoken")
class CheckinParsingIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "777:stub-bot-token";
    private static final long CHAT_ID = 9301L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubAnthropicServer CLAUDE = startClaude();
    private static final StubSttServer STT = startStt();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CheckinParsingService checkinParsingService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private CheckinRepository checkinRepository;

    @Autowired
    private InventoryTrendRepository inventoryTrendRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

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

    private static StubSttServer startStt() {
        try {
            return new StubSttServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the STT stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("claude.api-key", () -> "stub-anthropic-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
        registry.add("stt.api-key", () -> "stub-stt-key");
        registry.add("stt.endpoint", STT::endpoint);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        CLAUDE.close();
        STT.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        CLAUDE.reset();
        STT.reset();
        inventoryTrendRepository.deleteAll();
        checkinRepository.deleteAll();
        baselineBasketRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** A household with a baseline of four items and a chat already waiting for a check-in answer. */
    private User awaitingCheckin() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(item("Молоко 2.5%"), item("Хліб пшеничний"), item("Гречка"), item("Яйця С1")))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
        conversationStateService.save(
                CHAT_ID, ConversationFlow.CHECK_IN, CheckinPromptService.STEP_AWAITING_REPORT, Map.of());
        return user;
    }

    private static BasketItem item(String name) {
        return new BasketItem(UUID.randomUUID().toString(), name, "шт", BigDecimal.ONE, new BigDecimal("30"));
    }

    private static String delta(String stillHave, String runningLow, String gone) {
        return "{\"stillHave\":[%s],\"runningLow\":[%s],\"goneCompletely\":[%s]}"
                .formatted(stillHave, runningLow, gone);
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

    private void sendVoice(int updateId) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "voice":{"file_id":"voice-1","file_unique_id":"u1","duration":4,\
                                "mime_type":"audio/ogg"}}}""".formatted(updateId, updateId, CHAT_ID)))
                .andExpect(status().isOk());
    }

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void bucketsAMessyUkrainianAnswerIntoTheThreeGroups() {
        User user = awaitingCheckin();
        CLAUDE.respondWithText(delta("\"Молоко 2.5%\"", "\"Гречка\"", "\"Хліб пшеничний\""));

        CheckinResult result = checkinParsingService.parseText(user.getId(), "молоко ще є гречка на межі хліба нема");

        assertThat(result.needsClarification()).isFalse();
        assertThat(result.delta().stillHave()).containsExactly("Молоко 2.5%");
        assertThat(result.delta().runningLow()).containsExactly("Гречка");
        assertThat(result.delta().goneCompletely()).containsExactly("Хліб пшеничний");
    }

    @Test
    void putsTheBaselineInThePromptSoLoosePhrasingHasSomethingToMapOnto() {
        User user = awaitingCheckin();
        CLAUDE.respondWithText(delta("", "", "\"Яйця С1\""));

        checkinParsingService.parseText(user.getId(), "яйця закінчились");

        String request = CLAUDE.requests().getLast().toString();
        assertThat(request).contains("Хліб пшеничний").contains("Яйця С1");
    }

    @Test
    void dropsAnItemTheModelInventedRatherThanStoringIt() {
        User user = awaitingCheckin();
        CLAUDE.respondWithText(delta("\"Молоко 2.5%\", \"Ікра чорна\"", "", ""));

        CheckinResult result = checkinParsingService.parseText(user.getId(), "молоко є");

        assertThat(result.delta().stillHave()).containsExactly("Молоко 2.5%");
    }

    @Test
    void matchesLooseSpellingBackOntoTheBaselineName() {
        // Case and stray whitespace are the whole distance between a model's answer and the stored name.
        CheckinDelta filtered = CheckinParsingService.onlyBaselineItems(
                new CheckinDelta(List.of("  гречка "), List.of("ХЛІБ ПШЕНИЧНИЙ"), List.of()),
                List.of("Гречка", "Хліб пшеничний"));

        assertThat(filtered.stillHave()).containsExactly("Гречка");
        assertThat(filtered.runningLow()).containsExactly("Хліб пшеничний");
    }

    @Test
    void storesTheRawSentenceNextToWhatWasUnderstood() {
        User user = awaitingCheckin();
        CLAUDE.respondWithText(delta("\"Гречка\"", "", ""));

        checkinParsingService.parseText(user.getId(), "гречки ще повно");

        Checkin stored = checkinRepository
                .findFirstByUserIdOrderByReceivedAtDesc(user.getId())
                .orElseThrow();
        assertThat(stored.getRawInputText()).isEqualTo("гречки ще повно");
        assertThat(stored.getParsedDelta().stillHave()).containsExactly("Гречка");
    }

    @Test
    void recordsTheRawSentenceEvenWhenTheModelFails() {
        User user = awaitingCheckin();
        CLAUDE.injectStatus(400);

        CheckinResult result = checkinParsingService.parseText(user.getId(), "щось незрозуміле");

        assertThat(result.needsClarification()).isTrue();
        Checkin stored = checkinRepository
                .findFirstByUserIdOrderByReceivedAtDesc(user.getId())
                .orElseThrow();
        assertThat(stored.getRawInputText()).isEqualTo("щось незрозуміле");
        assertThat(stored.getParsedDelta()).isNull();
    }

    @Test
    void asksAgainInsteadOfRecordingAnEmptyAnswerAsUnchanged() throws Exception {
        awaitingCheckin();
        CLAUDE.respondWithText(delta("", "", ""));

        sendText(1, "ок");

        assertThat(lastMessageText()).contains("Не розібрав").contains("Молоко 2.5%");
        // Still waiting: the next message is an answer to the same question, not a new request.
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.CHECK_IN);
    }

    @Test
    void acknowledgesWhatItUnderstoodAndClosesTheFlow() throws Exception {
        awaitingCheckin();
        CLAUDE.respondWithText(delta("\"Молоко 2.5%\"", "", "\"Гречка\""));

        sendText(1, "молоко є, гречка скінчилась");

        assertThat(lastMessageText())
                .contains("Записав")
                .contains("Молоко 2.5%")
                .contains("Гречка");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void transcribesAVoiceNoteAndParsesTheTranscript() throws Exception {
        User user = awaitingCheckin();
        STT.respondWith("молоко ще є а хліба нема");
        CLAUDE.respondWithText(delta("\"Молоко 2.5%\"", "", "\"Хліб пшеничний\""));

        sendVoice(1);

        assertThat(STT.requestBodies()).hasSize(1);
        Checkin stored = checkinRepository
                .findFirstByUserIdOrderByReceivedAtDesc(user.getId())
                .orElseThrow();
        assertThat(stored.getRawInputText()).isEqualTo("молоко ще є а хліба нема");
        assertThat(stored.getParsedDelta().goneCompletely()).containsExactly("Хліб пшеничний");
        assertThat(lastMessageText()).contains("Записав");
    }

    @Test
    void aStoredCheckinMovesTheTrendCountersOnItsOwn() {
        User user = awaitingCheckin();
        CLAUDE.respondWithText(delta("\"Гречка\"", "", "\"Молоко 2.5%\""));

        checkinParsingService.parseText(user.getId(), "гречки повно, молоко скінчилось");

        // Storing a check-in and moving the counters is one step, so every channel gets it — including task 17's
        // photos.
        assertThat(inventoryTrendRepository
                        .findByUserIdAndItemName(user.getId(), "Гречка")
                        .orElseThrow()
                        .getConsecutiveUntouchedCycles())
                .isEqualTo(1);
        assertThat(inventoryTrendRepository
                        .findByUserIdAndItemName(user.getId(), "Молоко 2.5%")
                        .orElseThrow()
                        .getConsecutiveUntouchedCycles())
                .isZero();
    }

    @Test
    void asksForTextWhenTranscriptionFails() throws Exception {
        awaitingCheckin();
        STT.respondWithStatus(500);

        sendVoice(1);

        assertThat(lastMessageText()).contains("Напиши, будь ласка, текстом");
        assertThat(checkinRepository.count()).isZero();
    }
}
