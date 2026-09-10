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
import com.silporestockai.model.BasketItem;
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
    private com.silporestockai.repository.CheckinRepository checkinRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private com.silporestockai.repository.ShoppingListItemRepository shoppingListItemRepository;

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
        shoppingListItemRepository.deleteAll();
        checkinRepository.deleteAll();
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
        // A real, parseable instant, not "18:00" alone: CartSummary.deliverySlotStartsAt only carries a value —
        // and only then risks the "no JavaTimeModule" serialization crash asMap() once hit — when this actually
        // parses to one.
        MCP.respondToTool(
                "silpo_get_time_slots",
                "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"2026-09-03T18:00:00Z\"},"
                        + "{\"id\":\"slot-2\",\"from\":\"2026-09-04T20:00:00Z\"}]}");
        MCP.respondToTool("silpo_find_products_batch", """
                {"queries":[\
                {"query":"цибуля","products":[{"name":"цибуля","productId":"p-1","companyId":"company-3","branchId":"branch-7"}]},\
                {"query":"гречка","products":[{"name":"гречка","productId":"p-2","companyId":"company-3","branchId":"branch-7"}]}]}""");
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

    /**
     * Task 76: the realistic shape of the live failure. A household edits the list twice — «давай замість гречки
     * рис», then one more change — and by the final build the window picked at the start has been taken. The bot
     * used to answer «Кошик зібрати не вдалось: обраний час доставки більше недоступний. Виправ список і спробуй
     * ще раз», sending them to fix a list that was already right. Nothing is asked now: a free window is booked
     * and the cart message says the time moved.
     */
    @Test
    void aRevisionLoopThatOutlivesItsSlotRebooksInsteadOfBlamingTheList() {
        scriptSilpo();
        User user = onboardedUser();

        // Two rounds that build cleanly, as the «Змінити» loop does.
        cartConfirmationService.present(user, shoppingList());
        cartConfirmationService.present(user, shoppingList());

        // The third build finds the window gone: Silpo refuses the cart, and offers a later one instead.
        MCP.respondToToolInOrder(
                "silpo_get_shopping_cart_by_id",
                CART_WITH_A_STALE_SLOT,
                CART_WITH_A_STALE_SLOT,
                CART_WITH_A_STALE_SLOT,
                CART_ON_A_FRESH_SLOT);
        MCP.respondToTool(
                "silpo_get_time_slots",
                "{\"timeSlots\":[{\"id\":\"slot-9\",\"from\":\"2026-09-05T20:00:00Z\",\"available\":true}]}");

        boolean presented = cartConfirmationService.present(user, shoppingList());

        assertThat(presented).isTrue();
        String text = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(text).contains("Зібрав кошик").contains("підібрав найближчий вільний");
        assertThat(text).doesNotContain("Виправ список");
        assertThat(MCP.calledTools()).contains("silpo_update_shopping_cart");
    }

    private static final String CART_WITH_A_STALE_SLOT = """
            {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
            "items":[{"productId":"p-1","name":"Цибуля","unit":"кг","quantity":0.5,"price":25.5}],\
            "total":25.5,"validations":[{"level":"error","type":"timeslot","message":"timeslot.not_available"}]}""";

    private static final String CART_ON_A_FRESH_SLOT = """
            {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
            "items":[{"productId":"p-1","name":"Цибуля","unit":"кг","quantity":0.5,"price":25.5},\
            {"productId":"p-2","name":"Гречка","unit":"кг","quantity":1,"price":48}],\
            "total":73.5,"validations":[],\
            "checkoutWebLink":"https://silpo.ua/checkout/cart-1",\
            "checkoutMobileLink":"silpo://checkout/cart-1"}""";

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

    /**
     * A cart under Silpo's minimum order is shown exactly as built, with the shortfall and a «Докласти» button —
     * not silently doubled with the baseline's vegetables. The tap does the top-up and brings the confirm button.
     */
    @Test
    void aCartUnderTheMinimumOrderAsksBeforeToppingUpFromTheBaseline() throws Exception {
        User user = onboardedUser();
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(
                        new BasketItem("b-chicken", "Філе курчати", "кг", BigDecimal.ONE, new BigDecimal("700")),
                        new BasketItem("b-milk", "Молоко", "шт", new BigDecimal("2"), new BigDecimal("46")),
                        new BasketItem("b-bread", "Хліб", "шт", BigDecimal.ONE, new BigDecimal("28"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
        String context = """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery","items":[]}""";
        String refused = """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-1","name":"Цибуля","unit":"кг","quantity":0.5,"price":25.5},\
                {"productId":"p-2","name":"Гречка","unit":"кг","quantity":1,"price":48}],\
                "total":73.5,"productsTotal":73.5,"validations":[\
                {"level":"error","type":"order","message":"order.cost.min","context":{"orderCostMin":799}}],\
                "checkoutWebLink":null,"checkoutMobileLink":null}""";
        String toppedUp = """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-1","name":"Цибуля","unit":"кг","quantity":0.5,"price":25.5},\
                {"productId":"p-2","name":"Гречка","unit":"кг","quantity":1,"price":48},\
                {"productId":"b-bread","name":"Хліб","unit":"шт","quantity":1,"price":28},\
                {"productId":"b-milk","name":"Молоко","unit":"шт","quantity":2,"price":46},\
                {"productId":"b-chicken","name":"Філе курчати","unit":"кг","quantity":1,"price":700}],\
                "total":893.5,"productsTotal":893.5,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-1","checkoutMobileLink":"silpo://checkout/cart-1"}""";
        // Build: context read, refused read-back. Presenting reads the context once more for the slots. The
        // top-up: its own context read, then the read-back over the minimum.
        MCP.respondToToolInOrder("silpo_get_shopping_cart_by_id", context, refused, context, context, toppedUp);

        cartConfirmationService.present(user, shoppingList());

        JsonNode asked = TELEGRAM.sentMessages().getLast();
        assertThat(textOf(asked))
                .contains("Цибуля")
                .contains("Гречка")
                .contains("бракує")
                .contains("799");
        assertThat(asked.path("reply_markup").toString()).contains("Докласти").doesNotContain("Підтвердити");
        assertThat(MCP.callArguments("silpo_add_or_update_cart_products")).hasSize(1);
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow())
                .isEqualTo(ConversationFlow.CART_CONFIRMATION);

        tapButton(1, CartMessageService.CALLBACK_TOP_UP);

        assertThat(MCP.callArguments("silpo_add_or_update_cart_products")).hasSize(2);
        JsonNode offered = TELEGRAM.sentMessages().getLast();
        assertThat(textOf(offered))
                .contains("доклав із твого звичайного набору")
                .contains("+ Хліб — 1 шт — 28.00 грн")
                .contains("893.50")
                .doesNotContain("бракує");
        assertThat(offered.path("reply_markup").toString()).contains("Підтвердити");
        List<CustomerOrder> orders = customerOrderRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst().getItems()).hasSize(5);
    }

    /** Live: «хліб є» in the check-in, and the «Докласти» tap twenty minutes later brought two loaves. */
    @Test
    void theTopUpTapLeavesOutWhatTheCheckinSaidIsStillThere() throws Exception {
        User user = onboardedUser();
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(
                        new BasketItem("b-chicken", "Філе курчати", "кг", BigDecimal.ONE, new BigDecimal("700")),
                        new BasketItem("b-milk", "Молоко", "шт", new BigDecimal("2"), new BigDecimal("46")),
                        new BasketItem("b-bread", "Хліб", "шт", BigDecimal.ONE, new BigDecimal("28"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
        checkinRepository.save(com.silporestockai.entity.Checkin.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .rawInputText("хліб є")
                .parsedDelta(new com.silporestockai.model.CheckinDelta(List.of("Хліб"), List.of(), List.of()))
                .receivedAt(Instant.now())
                .build());
        String context = """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery","items":[]}""";
        String refused = """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-1","name":"Цибуля","unit":"кг","quantity":0.5,"price":25.5},\
                {"productId":"p-2","name":"Гречка","unit":"кг","quantity":1,"price":48}],\
                "total":73.5,"productsTotal":73.5,"validations":[\
                {"level":"error","type":"order","message":"order.cost.min","context":{"orderCostMin":799}}],\
                "checkoutWebLink":null,"checkoutMobileLink":null}""";
        String toppedUp = """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-1","name":"Цибуля","unit":"кг","quantity":0.5,"price":25.5},\
                {"productId":"p-2","name":"Гречка","unit":"кг","quantity":1,"price":48},\
                {"productId":"b-milk","name":"Молоко","unit":"шт","quantity":2,"price":46},\
                {"productId":"b-chicken","name":"Філе курчати","unit":"кг","quantity":1,"price":700}],\
                "total":865.5,"productsTotal":865.5,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-1","checkoutMobileLink":"silpo://checkout/cart-1"}""";
        MCP.respondToToolInOrder("silpo_get_shopping_cart_by_id", context, refused, context, context, toppedUp);
        cartConfirmationService.present(user, shoppingList());

        tapButton(1, CartMessageService.CALLBACK_TOP_UP);

        List<JsonNode> adds = MCP.callArguments("silpo_add_or_update_cart_products");
        assertThat(adds).hasSize(2);
        List<String> toppedUpIds = new java.util.ArrayList<>();
        adds.get(1)
                .path("products")
                .forEach(product -> toppedUpIds.add(product.path("productId").asText()));
        assertThat(toppedUpIds).contains("b-milk", "b-chicken").doesNotContain("b-bread");
        assertThat(textOf(TELEGRAM.sentMessages().getLast()))
                .doesNotContain("Хліб")
                .contains("865.50");
    }

    /** Without a baseline there is nothing to top up from, so the button is not offered and the message says so. */
    @Test
    void aCartUnderTheMinimumOrderWithNoBaselineOffersOnlyCancel() {
        User user = onboardedUser();
        String refused = """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-1","name":"Цибуля","unit":"кг","quantity":0.5,"price":25.5}],\
                "total":25.5,"productsTotal":25.5,"validations":[\
                {"level":"error","type":"order","message":"order.cost.min","context":{"orderCostMin":799}}],\
                "checkoutWebLink":null,"checkoutMobileLink":null}""";
        MCP.respondToToolInOrder("silpo_get_shopping_cart_by_id", refused, refused);

        cartConfirmationService.present(user, shoppingList());

        JsonNode asked = TELEGRAM.sentMessages().getLast();
        assertThat(textOf(asked)).contains("бракує").contains("застосунку «Сільпо»");
        assertThat(asked.path("reply_markup").toString()).contains("Скасувати").doesNotContain("Докласти");
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
        // Task 54: the money has to land on the row here, because confirm() never re-reads the cart from Silpo.
        // Without this the GMV panel would have nothing to sum and nobody would notice until the pitch.
        assertThat(orders.getFirst().getTotal()).isEqualByComparingTo("73.5");
        assertThat(orders.getFirst().getGoodsTotal()).isEqualByComparingTo("73.5");

        assertThat(lastMessageText()).contains("Цибуля").contains("Гречка").contains("73.50");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow())
                .isEqualTo(ConversationFlow.CART_CONFIRMATION);
    }

    @Test
    void theCartMessageShowsTheDeliverySlotAndOffersAnotherOne() {
        presentedCart();

        assertThat(lastMessageText()).contains("Доставка:");
        // Four buttons (a bonus variant of Підтвердити is offered) wrap into two rows on a phone; look in all of them.
        var keyboard = TELEGRAM.sentMessages().getLast().path("reply_markup").path("inline_keyboard");
        boolean hasSlotMenuButton = false;
        for (JsonNode row : keyboard) {
            for (JsonNode button : row) {
                if (CartMessageService.CALLBACK_SLOT_MENU.equals(
                        button.path("callback_data").asText())) {
                    hasSlotMenuButton = true;
                }
            }
        }
        assertThat(hasSlotMenuButton).isTrue();
    }

    @Test
    void pickingADifferentSlotUpdatesTheMessageWithoutBookingItYet() throws Exception {
        User user = presentedCart();

        tapButton(1, CartMessageService.CALLBACK_SLOT_MENU);
        var slotMenuButtons = TELEGRAM.sentMessages()
                .getLast()
                .path("reply_markup")
                .path("inline_keyboard")
                .get(0);
        assertThat(slotMenuButtons.size()).isEqualTo(2);

        tapButton(2, CartMessageService.CALLBACK_SLOT_PREFIX + "1");

        assertThat(MCP.calledTools()).doesNotContain("silpo_update_shopping_cart");
        CustomerOrder order = customerOrderRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId())
                .getFirst();
        assertThat(order.getDeliverySlot()).isEqualTo("slot-1"); // unchanged until confirm — this is the point
    }

    @Test
    void confirmingStoresTheOrderTheBaselineAndTheCheckoutLink() throws Exception {
        User user = presentedCart();
        // The list on screen when the cart was confirmed — proves confirm() flips its status.
        shoppingListItemRepository.save(ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .name("цибуля")
                .quantity(new BigDecimal("0.5"))
                .unit("кг")
                .build());

        tapButton(1, CartMessageService.CALLBACK_CONFIRM);

        CustomerOrder order = customerOrderRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId())
                .getFirst();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isNotNull();
        // Confirming keeps the total the household approved — this is what a confirmed row contributes to GMV.
        assertThat(order.getTotal()).isEqualByComparingTo("73.5");

        assertThat(shoppingListItemRepository.findByUserIdAndStatus(
                        user.getId(), com.silporestockai.model.ShoppingListStatus.ORDERED))
                .hasSize(1);
        assertThat(shoppingListItemRepository.findByUserIdAndStatus(
                        user.getId(), com.silporestockai.model.ShoppingListStatus.ACTIVE))
                .isEmpty();

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
    void confirmingAfterPickingADifferentSlotBooksItFirst() throws Exception {
        User user = presentedCart();

        tapButton(1, CartMessageService.CALLBACK_SLOT_MENU);
        tapButton(2, CartMessageService.CALLBACK_SLOT_PREFIX + "1");
        tapButton(3, CartMessageService.CALLBACK_CONFIRM);

        assertThat(MCP.calledTools()).contains("silpo_update_shopping_cart");
        // silpo_update_shopping_cart marks the cart's own delivery type, address and shipments required on every
        // call; sent as {cartId, timeslot: <id>} it was refused live on every run and the slot never changed.
        JsonNode update = MCP.callArguments("silpo_update_shopping_cart").getFirst();
        assertThat(update.path("shoppingCartId").asText()).isEqualTo("cart-1");
        assertThat(update.path("deliveryType").asText()).isEqualTo("delivery");
        assertThat(update.path("timeslot").path("start").asText()).isEqualTo("slot-2");
        assertThat(update.path("shipments").get(0).path("branchId").asText()).isEqualTo("branch-7");
        assertThat(update.path("shipments").get(0).path("companyId").asText()).isEqualTo("company-3");
        CustomerOrder order = customerOrderRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId())
                .getFirst();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getDeliverySlot()).isEqualTo("slot-2");
    }

    @Test
    void confirmingWithoutChangingTheSlotNeverCallsUpdateForIt() throws Exception {
        presentedCart();

        tapButton(1, CartMessageService.CALLBACK_CONFIRM);

        assertThat(MCP.calledTools()).doesNotContain("silpo_update_shopping_cart");
    }

    @Test
    void aFailedSlotBookingStillLeavesAConfirmedOrder() throws Exception {
        User user = presentedCart();
        MCP.failTool("silpo_update_shopping_cart");

        tapButton(1, CartMessageService.CALLBACK_SLOT_MENU);
        tapButton(2, CartMessageService.CALLBACK_SLOT_PREFIX + "1");
        tapButton(3, CartMessageService.CALLBACK_CONFIRM);

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CONFIRMED))
                .hasSize(1);
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
