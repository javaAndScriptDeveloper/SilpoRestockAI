package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OnboardingStep;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
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

@DisplayName("a new user is onboarded across several separate webhook calls")
class OnboardingFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "444:stub-bot-token";
    private static final long CHAT_ID = 7301L;
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
    private MealPlanRepository mealPlanRepository;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of("silpo_get_my_family"));
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
        // The onboarding hand-off generates a plan the moment a profile is saved; these tests never script a valid
        // plan answer, so what it leaves behind is cleaned rather than asserted on.
        mealPlanRepository.deleteAll();
        userProfileRepository.deleteAll();
        conversationStateRepository.deleteAll();
        tokenRepository.deleteAll();
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

    private void connectSilpo() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void greetsANewUserWithAConnectLinkAndRemembersTheStep() throws Exception {
        sendText(1, "привіт");

        assertThat(userRepository.findByTelegramChatId(CHAT_ID)).isPresent();
        var keyboard = TELEGRAM.sentMessages()
                .getFirst()
                .path("reply_markup")
                .path("inline_keyboard")
                .get(0);
        assertThat(keyboard.get(0).path("url").asText()).contains("/authorize");
        assertThat(keyboard.get(1).path("callback_data").asText()).isEqualTo("onb:skip");

        var state = conversationStateService.load(CHAT_ID);
        assertThat(state.getCurrentFlow()).isEqualTo(ConversationFlow.ONBOARDING);
        assertThat(state.getCurrentStep()).isEqualTo(OnboardingStep.AWAITING_CONNECT.name());
    }

    @Test
    void walksAConnectedUserFromConfirmationToASavedProfile() throws Exception {
        sendText(1, "привіт");
        connectSilpo();
        CLAUDE.respondWithText("""
                {"householdSize":4,"hasKids":true,"kidsAges":[3,7],\
                "dietaryRestrictions":["без горіхів"],"frequentItems":["молоко"]}""");

        tapButton(2, "onb:connected");
        assertThat(lastMessageText()).contains("4");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.CONFIRM_PROFILE.name());

        tapButton(3, "onb:confirm");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep()).isEqualTo(OnboardingStep.ASK_BUDGET.name());

        sendText(4, "2500 грн");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        assertThat(profile.getHouseholdSize()).isEqualTo(4);
        assertThat(profile.getHasKids()).isTrue();
        assertThat(profile.getKidsAges()).containsExactly(3, 7);
        assertThat(profile.getDietaryRestrictions()).containsExactly("без горіхів");
        assertThat(profile.getWeeklyBudget()).isEqualByComparingTo("2500");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void asksEverythingWhenTheUserSkipsConnecting() throws Exception {
        sendText(1, "привіт");

        tapButton(2, "onb:skip");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_HOUSEHOLD.name());

        sendText(3, "нас четверо");
        sendText(4, "алергія на горіхи");
        sendText(5, "броколі");
        sendText(6, "2000");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        assertThat(profile.getHouseholdSize()).isEqualTo(4);
        assertThat(profile.getDietaryRestrictions()).containsExactly("алергія на горіхи");
        assertThat(profile.getDislikedFoods()).containsExactly("броколі");
        assertThat(profile.getWeeklyBudget()).isEqualByComparingTo("2000");
        assertThat(MCP.callCount("tools/call")).isZero();
    }

    @Test
    void reAsksRatherThanStoringNonsense() throws Exception {
        sendText(1, "привіт");
        tapButton(2, "onb:skip");

        sendText(3, "не знаю");

        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_HOUSEHOLD.name());
        assertThat(lastMessageText()).isNotBlank();
    }

    @Test
    void resumesFromTheSavedStepAfterTheUserGoesSilent() throws Exception {
        sendText(1, "привіт");
        tapButton(2, "onb:skip");
        sendText(3, "нас четверо");

        // Nothing in memory carries between webhook calls; only conversation_state does.
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_RESTRICTIONS.name());

        sendText(4, "нема");

        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_DISLIKES.name());
        assertThat(conversationStateService.load(CHAT_ID).getContext()).containsEntry("householdSize", 4);
        assertThat(userProfileRepository.count()).isZero();
    }

    @Test
    void correctionOverwritesADetectedField() throws Exception {
        sendText(1, "привіт");
        connectSilpo();
        CLAUDE.respondWithText("{\"householdSize\":4,\"hasKids\":false}");
        tapButton(2, "onb:connected");

        tapButton(3, "onb:correct");
        sendText(4, "нас двоє");
        sendText(5, "нема");
        sendText(6, "нема");
        sendText(7, "1800");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        assertThat(profile.getHouseholdSize()).isEqualTo(2);
        assertThat(profile.getWeeklyBudget()).isEqualByComparingTo("1800");
    }

    @Test
    void anOnboardedUserIsNotOnboardedAgain() throws Exception {
        sendText(1, "привіт");
        tapButton(2, "onb:skip");
        sendText(3, "2");
        sendText(4, "нема");
        sendText(5, "нема");
        sendText(6, "1500");
        TELEGRAM.reset();

        sendText(7, "а що далі?");

        assertThat(userProfileRepository.count()).isEqualTo(1);
        assertThat(lastMessageText()).contains("Профіль уже є");
        assertThat(mealPlanRepository.count()).isZero();
    }

    @Test
    void degradesToAskingWhenSilpoIsUnreachable() throws Exception {
        sendText(1, "привіт");
        connectSilpo();
        MCP.injectStatus("initialize", 500);

        tapButton(2, "onb:connected");

        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_HOUSEHOLD.name());
        assertThat(userProfileRepository.count()).isZero();
    }
}
