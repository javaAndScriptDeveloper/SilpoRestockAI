package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
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

/**
 * «Де моє замовлення» (task 56) and the «📦 Замовлення» button (task 57): one read of the two read-only MCP
 * history tools, answered from what Silpo actually returned and from nothing else.
 */
@DisplayName("order status from the real Silpo history (tasks 56 and 57)")
class OrderStatusIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "5656:stub-bot-token";
    private static final long CHAT_ID = 5601L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    private static final String CLASSIFIED =
            "{\"intent\":\"WHERE_IS_MY_ORDER\",\"confidence\":0.94,\"themeDescription\":null,\"targetDateTimeIso\":null}";

    private static final String ONLINE_ORDERS = """
            {"orders":[
              {"orderId":"A-100","date":"2026-08-20T10:00:00Z","total":310.5,"status":"Delivered",
               "items":[{"productId":"p-milk","name":"Молоко 2.5% 1л","quantity":2,"unit":"шт","price":90.00}]},
              {"orderId":"A-200","date":"2026-09-06T18:30:00Z","total":1234.56,"status":"Готується",
               "deliveryTime":{"from":"2026-09-07T10:00:00+03:00","to":"2026-09-07T12:00:00+03:00"},
               "items":[{"productId":"p-eggs","name":"Яйця С0 10шт","quantity":1,"unit":"шт","price":75.00}]}
            ]}""";

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
    private ShoppingListItemRepository shoppingListItemRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_online_orders",
                    "silpo_get_my_offline_orders",
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id"));
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
        shoppingListItemRepository.deleteAll();
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
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-s\"}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-s","branchId":"branch-7","companyId":"company-3","deliveryType":"DeliveryHome",\
                "timeslot":{"start":"2026-09-07T06:00:00+00:00","end":"2026-09-07T07:30:00+00:00"},"items":[]}""");
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

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void answersTheChatPhraseWithTheNewestOrderFromTheRealHistory() throws Exception {
        CLAUDE.respondWithText(CLASSIFIED);
        MCP.respondToTool("silpo_get_my_online_orders", ONLINE_ORDERS);
        MCP.respondToTool("silpo_get_my_offline_orders", "{\"orders\":[]}");

        sendText(1, "де моє замовлення?");

        assertThat(lastMessageText())
                .contains("6 вер")
                .contains("1234.56 грн")
                .contains("Готується")
                .contains("7 вересня, 10:00–12:00");
        // Read-only: the status check never opens a flow and never touches the list.
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
        assertThat(shoppingListItemRepository.findAll()).isEmpty();
    }

    @Test
    void saysSoWhenTheAccountHasNoOrders() throws Exception {
        CLAUDE.respondWithText(CLASSIFIED);
        MCP.respondToTool("silpo_get_my_online_orders", "{\"orders\":[]}");
        MCP.respondToTool("silpo_get_my_offline_orders", "{\"orders\":[]}");

        sendText(1, "коли приїде доставка?");

        assertThat(lastMessageText()).contains("Не бачу замовлень");
    }

    @Test
    void saysSoWhenNeitherHistoryToolAnswers() throws Exception {
        CLAUDE.respondWithText(CLASSIFIED);
        MCP.failTool("silpo_get_my_online_orders");
        MCP.failTool("silpo_get_my_offline_orders");

        sendText(1, "що там із моїм замовленням?");

        // Not the same sentence as an empty history: «could not check» is a different fact from «none».
        assertThat(lastMessageText()).contains("Не зміг дістати");
    }

    @Test
    void asksToConnectSilpoWhenTheAccountIsNotLinked() throws Exception {
        CLAUDE.respondWithText(CLASSIFIED);
        tokenRepository.deleteAll();

        sendText(1, "де моє замовлення?");

        assertThat(lastMessageText()).contains("під'єднай акаунт");
        assertThat(MCP.calledTools()).doesNotContain("silpo_get_my_online_orders");
    }

    @Test
    void theButtonAnswersTheSameFactsInMoreDetail() throws Exception {
        MCP.respondToTool("silpo_get_my_online_orders", ONLINE_ORDERS);
        MCP.respondToTool("silpo_get_my_offline_orders", "{\"orders\":[]}");

        sendText(1, "📦 Замовлення");

        String text = lastMessageText();
        assertThat(text).contains("1234.56 грн").contains("Готується").contains("7 вересня, 10:00–12:00");
        // The management view also lists what came before, with Silpo's own English status translated.
        assertThat(text).contains("20 серп").contains("310.50 грн").contains("Доставлено");
        // A button tap is navigation, not a sentence: nothing is sent to the classifier.
        assertThat(CLAUDE.callCount()).isZero();
        // One read of the history per interaction, not one per line shown.
        assertThat(MCP.calledTools().stream().filter("silpo_get_my_online_orders"::equals))
                .hasSize(1);
    }

    @Test
    void theButtonSaysTheSameThingAsTheIntentWhenTheHistoryIsEmpty() throws Exception {
        MCP.respondToTool("silpo_get_my_online_orders", "{\"orders\":[]}");
        MCP.respondToTool("silpo_get_my_offline_orders", "{\"orders\":[]}");

        sendText(1, "📦 Замовлення");

        assertThat(lastMessageText()).contains("Не бачу замовлень");
    }
}
