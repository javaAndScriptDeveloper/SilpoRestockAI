package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.ShoppingListSourceType;
import com.silporestockai.model.ShoppingListStatus;
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

@DisplayName("seeding the list from a real past Silpo order (task 35)")
class PastOrderSeedIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "3535:stub-bot-token";
    private static final long CHAT_ID = 3501L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    private static final String CLASSIFIED =
            "{\"intent\":\"PAST_ORDER_SEED\",\"confidence\":0.93,\"themeDescription\":null,\"targetDateTimeIso\":null}";

    private static final String ONLINE_ORDERS = """
            {"orders":[
              {"orderId":"A-100","date":"2026-08-20T10:00:00Z","total":310.5,
               "items":[{"productId":"p-milk","name":"Молоко 2.5% 1л","quantity":2,"unit":"шт","price":90.00},
                        {"productId":"p-bread","name":"Хліб житній","quantity":1,"unit":"шт","price":42.50}]},
              {"orderId":"A-200","date":"2026-09-01T18:30:00Z","total":1234.56,
               "items":[{"productId":"p-eggs","name":"Яйця С0 10шт","quantity":1,"unit":"шт","price":75.00},
                        {"productId":"p-cheese","name":"Сир твердий","quantity":0.4,"unit":"кг","price":160.00},
                        {"productId":"p-milk","name":"Молоко 2.5% 1л","quantity":3,"unit":"шт","price":135.00}]}
            ]}""";

    private static final String OFFLINE_ORDERS = """
            {"orders":[{"id":"S-7","createdAt":"2026-08-25","sum":80,
               "items":[{"productId":"p-water","name":"Вода 1.5л","quantity":4,"price":80}]}]}""";

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
            return new StubMcpServer(
                    List.of("silpo_get_my_online_orders", "silpo_get_my_offline_orders", "silpo_find_products_batch"));
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

    private void tapButton(int updateId, String data) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"callback_query":{"id":"cb-%d","chat_instance":"ci",\
                                "from":{"id":5,"is_bot":false,"first_name":"Тест"},"data":"%s",\
                                "message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}}}}""".formatted(updateId, updateId, data, updateId, CHAT_ID)))
                .andExpect(status().isOk());
    }

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void offersRecentOrdersNewestFirstAndSeedsTheListFromThePickedOne() throws Exception {
        CLAUDE.respondWithText(CLASSIFIED);
        MCP.respondToTool("silpo_get_my_online_orders", ONLINE_ORDERS);
        MCP.respondToTool("silpo_get_my_offline_orders", OFFLINE_ORDERS);

        sendText(1, "зроби список як минулого разу");

        var offer = TELEGRAM.sentMessages().getLast();
        assertThat(offer.path("text").asText()).contains("Яке взяти за основу?");
        var keyboard = offer.path("reply_markup").path("inline_keyboard");
        List<String> labels =
                keyboard.findValues("text").stream().map(n -> n.asText()).toList();
        List<String> callbacks = keyboard.findValues("callback_data").stream()
                .map(n -> n.asText())
                .toList();
        assertThat(callbacks).containsExactly("past:0", "past:1", "past:2", "past:cancel");
        // Newest first across both tools: 1 вересня (online), 25 серпня (offline, marked), 20 серпня (online).
        assertThat(labels.get(0)).contains("3 позиції").contains("1234.56 грн");
        assertThat(labels.get(1)).contains("магазин").contains("80.00 грн");
        assertThat(labels.get(2)).contains("2 позиції").contains("310.50 грн");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.PAST_ORDER_PICK);

        tapButton(2, "past:0");

        List<ShoppingListItem> items =
                shoppingListItemRepository.findByUserIdAndStatus(user.getId(), ShoppingListStatus.ACTIVE);
        assertThat(items).hasSize(3);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getSilpoProductId()).isNotBlank();
            assertThat(item.getSourceType()).isEqualTo(ShoppingListSourceType.PAST_ORDER);
            assertThat(item.getEstimatedPrice()).isNotNull();
        });
        ShoppingListItem milk = items.stream()
                .filter(item -> item.getName().startsWith("Молоко"))
                .findFirst()
                .orElseThrow();
        assertThat(milk.getSilpoProductId()).isEqualTo("p-milk");
        assertThat(milk.getQuantity()).isEqualByComparingTo("3");
        // A line price of 135 for 3 → a unit price of 45, so the list's estimate (task 39) is right immediately.
        assertThat(milk.getEstimatedPrice()).isEqualByComparingTo("45.00");
        // The whole point: real ids, no catalog search.
        assertThat(MCP.calledTools()).doesNotContain("silpo_find_products_batch");
        assertThat(lastMessageText()).contains("Ось що пропоную взяти").contains("Орієнтовно ~370.00 грн");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.LIST_BUILDING);
        assertThat(TELEGRAM.sentMessages().stream().map(m -> m.path("text").asText()))
                .anyMatch(text -> text.contains("Взяв за основу замовлення") && text.contains("ті самі товари"));
    }

    @Test
    void saysSoWhenTheAccountHasNoOrders() throws Exception {
        CLAUDE.respondWithText(CLASSIFIED);
        MCP.respondToTool("silpo_get_my_online_orders", "{\"orders\":[]}");
        MCP.respondToTool("silpo_get_my_offline_orders", "{\"orders\":[]}");

        sendText(1, "почни з мого останнього замовлення");

        assertThat(lastMessageText()).contains("Не бачу минулих замовлень");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void anOrderTheToolReturnsWithoutLinesIsRefusedHonestly() throws Exception {
        CLAUDE.respondWithText(CLASSIFIED);
        MCP.respondToTool(
                "silpo_get_my_online_orders",
                "{\"orders\":[{\"orderId\":\"A-9\",\"date\":\"2026-09-02\",\"total\":50}]}");
        MCP.respondToTool("silpo_get_my_offline_orders", "{\"orders\":[]}");

        sendText(1, "зроби список як минулого разу");
        tapButton(2, "past:0");

        assertThat(lastMessageText()).contains("без переліку позицій");
        assertThat(shoppingListItemRepository.findByUserIdAndStatus(user.getId(), ShoppingListStatus.ACTIVE))
                .isEmpty();
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void cancelLeavesTheCurrentListAlone() throws Exception {
        CLAUDE.respondWithText(CLASSIFIED);
        MCP.respondToTool("silpo_get_my_online_orders", ONLINE_ORDERS);
        MCP.respondToTool("silpo_get_my_offline_orders", "{\"orders\":[]}");

        sendText(1, "зроби список як минулого разу");
        tapButton(2, "past:cancel");

        assertThat(lastMessageText()).contains("лишаю як є");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }
}
