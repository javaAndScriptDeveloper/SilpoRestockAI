package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.ConversationState;
import com.silporestockai.entity.Feedback;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.DietType;
import com.silporestockai.model.FeedbackSource;
import com.silporestockai.model.OnboardingStep;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.FeedbackRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.math.BigDecimal;
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

@DisplayName("the «Фідбек» button (task 47)")
class FeedbackIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "777:stub-bot-token";
    private static final long CHAT_ID = 4701L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private FeedbackRepository feedbackRepository;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of());
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("telegram.web-app-base-url", () -> "https://example.test");
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        MCP.reset();
        CLAUDE.reset();
        feedbackRepository.deleteAll();
        userProfileRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void deliver(String body) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void sendText(int updateId, String text) throws Exception {
        deliver("""
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "text":"%s"}}""".formatted(updateId, updateId, CHAT_ID, text));
    }

    private void tapButton(int updateId, String data) throws Exception {
        deliver("""
                {"update_id":%d,"callback_query":{"id":"cb-%d","chat_instance":"ci",\
                "from":{"id":5,"is_bot":false,"first_name":"Тест"},"data":"%s",\
                "message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}}}}""".formatted(updateId, updateId, data, updateId, CHAT_ID));
    }

    private User onboardedUser() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .adultMaleCount(1)
                .adultFemaleCount(1)
                .childrenAgeBrackets(List.of())
                .householdSize(2)
                .hasKids(false)
                .dietaryRestrictions(List.of())
                .dietType(DietType.NONE)
                .cookingTimePreference(CookingTimePreference.COOKS_DAILY)
                .weeklyBudget(new BigDecimal("2000"))
                .onlyUaProducer(false)
                .build());
        return user;
    }

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void theButtonCapturesTheNextMessageRawAndLeavesAnIdleChatIdle() throws Exception {
        User user = onboardedUser();

        sendText(1, "💬 Фідбек");
        assertThat(lastMessageText()).contains("Що не так або що покращити?");
        assertThat(TELEGRAM.sentMessages()
                        .getLast()
                        .path("reply_markup")
                        .path("inline_keyboard")
                        .get(0)
                        .get(0)
                        .path("callback_data")
                        .asText())
                .isEqualTo("fb:cancel");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.FEEDBACK);

        sendText(2, "  кнопка Список не там, де я шукав  ");

        assertThat(lastMessageText()).isEqualTo("Дякую, врахуємо.");
        List<Feedback> stored = feedbackRepository.findAll();
        assertThat(stored).hasSize(1);
        Feedback feedback = stored.getFirst();
        assertThat(feedback.getRawText()).isEqualTo("кнопка Список не там, де я шукав");
        assertThat(feedback.getTelegramChatId()).isEqualTo(CHAT_ID);
        assertThat(feedback.getUserId()).isEqualTo(user.getId());
        assertThat(feedback.getSource()).isEqualTo(FeedbackSource.BUTTON);
        assertThat(feedback.getCreatedAt()).isNotNull();
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void feedbackMidFlowPutsTheChatBackExactlyWhereItWas() throws Exception {
        onboardedUser();
        conversationStateService.save(
                CHAT_ID, ConversationFlow.LIST_BUILDING, "AWAITING_APPROVAL", Map.of("draftId", "d-1", "attempt", 2));

        sendText(1, "💬 Фідбек");
        sendText(2, "список довгий, не видно кінця");

        ConversationState restored = conversationStateService.load(CHAT_ID);
        assertThat(restored.getCurrentFlow()).isEqualTo(ConversationFlow.LIST_BUILDING);
        assertThat(restored.getCurrentStep()).isEqualTo("AWAITING_APPROVAL");
        assertThat(restored.getContext()).containsEntry("draftId", "d-1").containsEntry("attempt", 2);
        assertThat(feedbackRepository.count()).isEqualTo(1);
    }

    @Test
    void feedbackWorksBeforeOnboardingIsFinished() throws Exception {
        sendText(1, "привіт");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.AWAITING_CONNECT.name());

        sendText(2, "/feedback");
        sendText(3, "не розумію, куди тиснути");

        Feedback feedback = feedbackRepository.findAll().getFirst();
        assertThat(feedback.getRawText()).isEqualTo("не розумію, куди тиснути");
        assertThat(feedback.getUserId())
                .isEqualTo(userRepository
                        .findByTelegramChatId(CHAT_ID)
                        .orElseThrow()
                        .getId());
        ConversationState restored = conversationStateService.load(CHAT_ID);
        assertThat(restored.getCurrentFlow()).isEqualTo(ConversationFlow.ONBOARDING);
        assertThat(restored.getCurrentStep()).isEqualTo(OnboardingStep.AWAITING_CONNECT.name());
    }

    @Test
    void aPersistentMenuTapAbandonsAnOpenPromptInsteadOfBeingFiledAsFeedback() throws Exception {
        onboardedUser();

        sendText(1, "💬 Фідбек");
        sendText(2, "❓ Інструкція");

        assertThat(feedbackRepository.count()).isZero();
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isNotEqualTo(ConversationFlow.FEEDBACK);
        // The button still did its job.
        assertThat(lastMessageText()).doesNotContain("Дякую, врахуємо");
    }

    @Test
    void cancelRestoresThePreviousStateWithoutStoringAnything() throws Exception {
        onboardedUser();
        conversationStateService.save(CHAT_ID, ConversationFlow.CHECK_IN, "AWAITING_ANSWER", Map.of());

        sendText(1, "💬 Фідбек");
        tapButton(2, "fb:cancel");

        assertThat(feedbackRepository.count()).isZero();
        ConversationState restored = conversationStateService.load(CHAT_ID);
        assertThat(restored.getCurrentFlow()).isEqualTo(ConversationFlow.CHECK_IN);
        assertThat(restored.getCurrentStep()).isEqualTo("AWAITING_ANSWER");
        assertThat(lastMessageText()).isEqualTo("Гаразд, без фідбеку.");
    }
}
