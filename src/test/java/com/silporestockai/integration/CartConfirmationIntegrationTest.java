package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CartConfirmationService;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.telegram.CartMessageService;
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

@DisplayName("the cart is confirmed or cancelled from Telegram, and a confirmed one becomes the baseline")
class CartConfirmationIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "555:stub-bot-token";
    private static final long CHAT_ID = 7701L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CartConfirmationService cartConfirmationService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

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
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products",
                    "silpo_update_shopping_cart"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        MCP.reset();
        baselineBasketRepository.deleteAll();
        customerOrderRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        scriptSilpo();
    }

    /** The whole six-call sequence, ending in a cart with two lines, a total and 120 spendable bonuses. */
    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-1\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_find_products_batch", """
                {"products":[\
                {"name":"цибуля","productId":"p-1","companyId":"company-3","branchId":"branch-7"},\
                {"name":"гречка","productId":"p-2","companyId":"company-3","branchId":"branch-7"}]}""");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_update_shopping_cart", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-1","name":"Цибуля","unit":"кг","quantity":0.5,"price":25.5},\
                {"productId":"p-2","name":"Гречка","unit":"кг","quantity":1,"price":48}],\
                "total":73.5,"validations":[],\
                "loyalty":{"bonusAvailable":120,"bonusRequested":null,"isEnabled":true},\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-1",\
                "checkoutMobileLink":"silpo://checkout/cart-1"}""");
    }

    /** An onboarded, Silpo-connected user — the only kind that ever reaches a cart. */
    private User onboardedUser() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .weeklyBudget(new BigDecimal("2500"))
                .build());
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user;
    }

    private static List<ShoppingListItem> shoppingList() {
        return List.of(
                ShoppingListItem.builder()
                        .id(UUID.randomUUID())
                        .name("цибуля")
                        .quantity(new BigDecimal("0.5"))
                        .unit("кг")
                        .build(),
                ShoppingListItem.builder()
                        .id(UUID.randomUUID())
                        .name("гречка")
                        .quantity(BigDecimal.ONE)
                        .unit("кг")
                        .build());
    }

    private User presentedCart() {
        User user = onboardedUser();
        cartConfirmationService.present(user, shoppingList());
        return user;
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

    private static String textOf(JsonNode message) {
        return message.path("text").asText();
    }

    private String lastMessageText() {
        return textOf(TELEGRAM.sentMessages().getLast());
    }

    @Test
    void presentingWritesADraftOrderAndShowsEveryLineWithTheTotal() {
        User user = presentedCart();

        List<CustomerOrder> orders = customerOrderRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst().getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(orders.getFirst().getType()).isEqualTo(OrderType.INITIAL);
        assertThat(orders.getFirst().getSilpoCartId()).isEqualTo("cart-1");
        assertThat(orders.getFirst().getDeliverySlot()).isEqualTo("slot-1");
        assertThat(orders.getFirst().getItems()).hasSize(2);

        assertThat(lastMessageText()).contains("Цибуля").contains("Гречка").contains("73.50");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow())
                .isEqualTo(ConversationFlow.CART_CONFIRMATION);
    }

    @Test
    void confirmingStoresTheOrderTheBaselineAndTheCheckoutLink() throws Exception {
        User user = presentedCart();

        tapButton(1, CartMessageService.CALLBACK_CONFIRM);

        CustomerOrder order = customerOrderRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId())
                .getFirst();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isNotNull();

        BaselineBasket baseline = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow();
        assertThat(baseline.getItems()).hasSize(2);
        assertThat(baseline.getItems().getFirst().name()).isEqualTo("Цибуля");

        JsonNode confirmation = TELEGRAM.sentMessages().getLast();
        assertThat(textOf(confirmation)).contains("еталонний набір");
        assertThat(confirmation.toString()).contains("https://silpo.ua/checkout/cart-1");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void confirmingWithBonusesSpendsThemThroughSilpoFirst() throws Exception {
        presentedCart();

        tapButton(1, CartMessageService.CALLBACK_CONFIRM_BONUS);

        assertThat(MCP.calledTools()).contains("silpo_update_shopping_cart");
        assertThat(lastMessageText()).contains("Списав бонусів: 120");
    }

    @Test
    void aSecondConfirmTapChangesNothing() throws Exception {
        User user = presentedCart();

        tapButton(1, CartMessageService.CALLBACK_CONFIRM_BONUS);
        int messagesAfterFirstTap = TELEGRAM.sentMessages().size();
        long bonusCallsAfterFirstTap = MCP.calledTools().stream()
                .filter("silpo_update_shopping_cart"::equals)
                .count();

        tapButton(2, CartMessageService.CALLBACK_CONFIRM_BONUS);

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CONFIRMED))
                .hasSize(1);
        assertThat(baselineBasketRepository.findByUserIdOrderByConfirmedAtDesc(user.getId()))
                .hasSize(1);
        assertThat(bonusCallsAfterFirstTap).isEqualTo(1);
        assertThat(MCP.calledTools().stream()
                        .filter("silpo_update_shopping_cart"::equals)
                        .count())
                .isEqualTo(1);
        assertThat(TELEGRAM.sentMessages()).hasSize(messagesAfterFirstTap);
        assertThat(TELEGRAM.callbackAnswers()).hasSize(2);
    }

    @Test
    void cancellingMarksTheOrderAndLeavesTheBaselineAlone() throws Exception {
        User user = presentedCart();

        tapButton(1, CartMessageService.CALLBACK_CANCEL);

        assertThat(customerOrderRepository
                        .findByUserIdOrderByCreatedAtDesc(user.getId())
                        .getFirst()
                        .getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(baselineBasketRepository.findByUserIdOrderByConfirmedAtDesc(user.getId()))
                .isEmpty();
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }

    @Test
    void asecondConfirmedBasketSupersedesTheFirstInsteadOfDeletingIt() throws Exception {
        User user = presentedCart();
        tapButton(1, CartMessageService.CALLBACK_CONFIRM);

        cartConfirmationService.present(user, shoppingList());
        tapButton(2, CartMessageService.CALLBACK_CONFIRM);

        assertThat(baselineBasketRepository.findByUserIdOrderByConfirmedAtDesc(user.getId()))
                .hasSize(2);
        assertThat(baselineBasketRepository.findByUserIdAndIsCurrentTrue(user.getId()))
                .isPresent();
    }

    @Test
    void aRefusedBonusCallStillLeavesAConfirmedOrder() throws Exception {
        User user = presentedCart();
        MCP.failTool("silpo_update_shopping_cart");

        tapButton(1, CartMessageService.CALLBACK_CONFIRM_BONUS);

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CONFIRMED))
                .hasSize(1);
        assertThat(TELEGRAM.sentMessages().stream().map(CartConfirmationIntegrationTest::textOf))
                .anyMatch(text -> text.contains("Бонуси списати не вдалось"));
    }

    @Test
    void aTapWithNoDraftBehindItIsIgnored() throws Exception {
        onboardedUser();
        conversationStateService.save(
                CHAT_ID, ConversationFlow.CART_CONFIRMATION, "AWAITING_DECISION", java.util.Map.of());
        TELEGRAM.reset();

        tapButton(1, CartMessageService.CALLBACK_CONFIRM);

        assertThat(customerOrderRepository.findAll()).isEmpty();
        assertThat(TELEGRAM.sentMessages()).isEmpty();
        assertThat(TELEGRAM.callbackAnswers()).hasSize(1);
    }

    /**
     * The one cart failure with an actual fix a person can perform themselves. Everything else says "try again
     * later"; this one says what to go and do, because "try again later" would never resolve it on its own.
     */
    @Test
    void aGuestWithNoSavedAddressIsToldToAddOneRatherThanToTryAgainLater() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"success\":true,\"shoppingCartId\":null,\"exists\":false}");
        MCP.respondToTool("silpo_get_my_delivery_addresses", "{\"addresses\":[]}");
        User user = onboardedUser();

        cartConfirmationService.present(user, shoppingList());

        assertThat(lastMessageText()).contains("адрес").contains("Сільпо");
        assertThat(lastMessageText()).doesNotContain("Спробую ще раз трохи пізніше");
        assertThat(customerOrderRepository.findAll()).isEmpty();
    }
}
