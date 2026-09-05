package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CheckinDelta;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.ConversationStateRepository;
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
import java.math.BigDecimal;
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

/**
 * Task 26: nothing a user does may end in silence, a raw exception, or a dead-end conversation state — even
 * for a failure no specific flow already catches locally.
 */
@DisplayName("an exhausted-retry MCP/Claude failure gets a friendly message, never silence")
class TelegramFailureRecoveryIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "7070:stub-bot-token";
    private static final long CHAT_ID = 18301L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private CheckinRepository checkinRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of("silpo_get_my_shopping_cart", "silpo_get_shopping_cart_by_id"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
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
        baselineBasketRepository.deleteAll();
        checkinRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(new BasketItem("p-1", "Молоко", "л", BigDecimal.ONE, new BigDecimal("38"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
        checkinRepository.save(Checkin.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .rawInputText("stub")
                .parsedDelta(new CheckinDelta(List.of(), List.of(), List.of("Молоко")))
                .receivedAt(Instant.now())
                .build());
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

    @Test
    void anMcpFailureDuringReorderGetsAFriendlyMessageAndALiveConversationAfterwards() throws Exception {
        // silpo_get_my_shopping_cart failing here is not caught anywhere upstream of
        // TelegramRoutingService — ReorderService.build() calls CartBuildingService.getOrCreateCartContext
        // directly, with nothing local to translate the failure.
        MCP.failTool("silpo_get_my_shopping_cart");

        sendText(1, "/reorder");

        String text = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(text).contains("Сільпо").doesNotContain("Exception").doesNotContain("at com.silporestockai");
        assertThat(conversationStateRepository.findById(CHAT_ID).orElseThrow().getCurrentFlow())
                .isEqualTo(ConversationFlow.NONE);

        // A dead-end state would mean this follow-up produces another error or nothing at all.
        MCP.reset();
        sendText(2, "щось незрозуміле бурмотіння");
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).isNotBlank();
    }

    @Test
    void aClaudeFailureDuringSpecialModeRegenerationGetsAFriendlyMessageAndALiveConversationAfterwards()
            throws Exception {
        // First get into MEDICAL_GASTRITIS_ACUTE for real: classify, then a valid plan generation.
        CLAUDE.respondWithTexts("""
                {"intent":"SPECIAL_MODE_MEDICAL_GASTRITIS","confidence":0.95,\
                "themeDescription":null,"targetDateTimeIso":null}""", MealPlanIntegrationTest.fullWeekJson());
        sendText(1, "я захворів, гастрит");

        // The successful trigger above left conversation_state in LIST_BUILDING/AWAITING_APPROVAL (the
        // freshly presented plan) — sending "/normal" as free text there would be read as a list-edit
        // instruction instead of the slash command. Force it back to NONE first, same as any other message
        // sent outside an active flow.
        conversationStateService.save(CHAT_ID, ConversationFlow.NONE, null, java.util.Map.of());

        // 400 maps to a plain (non-retried) ClaudeApiException — SpecialModeService.cancel() also calls
        // regenerateAndPresent, with no local catch around the Claude call at all.
        CLAUDE.reset();
        CLAUDE.injectStatus(400);

        sendText(2, "/normal");

        String text = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(text).contains("Не вдалось згенерувати").doesNotContain("Exception");
        assertThat(conversationStateRepository.findById(CHAT_ID).orElseThrow().getCurrentFlow())
                .isEqualTo(ConversationFlow.NONE);

        CLAUDE.reset();
        sendText(3, "/uaonly");
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).isNotBlank();
    }
}
