package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CheckinDelta;
import com.silporestockai.model.DeltaOrder;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.InventoryTrendRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.TrustLevelRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ReorderConfirmationService;
import com.silporestockai.service.ReorderService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.telegram.ReorderMessageService;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
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

@DisplayName("a reorder is confirmed item by item, and only an edited one becomes the new baseline")
class ReorderConfirmationIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "888:stub-bot-token";
    private static final long CHAT_ID = 10101L;
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReorderService reorderService;

    @Autowired
    private ReorderConfirmationService reorderConfirmationService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private CheckinRepository checkinRepository;

    @Autowired
    private InventoryTrendRepository inventoryTrendRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private TrustLevelRepository trustLevelRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

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
                    "silpo_update_shopping_cart",
                    "silpo_get_promotions",
                    "silpo_get_replacements"));
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
        trustLevelRepository.deleteAll();
        inventoryTrendRepository.deleteAll();
        checkinRepository.deleteAll();
        baselineBasketRepository.deleteAll();
        customerOrderRepository.deleteAll();
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
                .items(List.of(
                        new BasketItem("p-1", "Молоко", "л", new BigDecimal("2"), new BigDecimal("38")),
                        new BasketItem("p-2", "Хліб", "шт", BigDecimal.ONE, new BigDecimal("25"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
        scriptSilpo();
    }

    /** Milk resolves and lands in the cart; bread never does, so it becomes a substitute question. */
    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-9\"}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-9","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-1","name":"Молоко 2.5%","unit":"л","quantity":2,"price":38}],\
                "total":76,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-9","checkoutMobileLink":"silpo://checkout/cart-9"}""");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_update_shopping_cart", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_promotions", "{\"promotions\":[]}");
        MCP.respondToTool(
                "silpo_find_products_batch",
                "{\"products\":[{\"name\":\"Молоко\",\"productId\":\"p-1\",\"branchId\":\"branch-7\"}]}");
        MCP.respondToTool(
                "silpo_get_replacements",
                "{\"replacements\":[{\"productId\":\"p-77\",\"name\":\"Хліб житній\",\"price\":27}]}");
        MCP.respondToTool("silpo_get_time_slots", slotsJson());
    }

    /** Two windows: the coming Wednesday and the coming Friday, in the order a server would list them. */
    private static String slotsJson() {
        LocalDate wednesday = LocalDate.now(KYIV).with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));
        LocalDate friday = LocalDate.now(KYIV).with(TemporalAdjusters.next(DayOfWeek.FRIDAY));
        return "{\"timeSlots\":[{\"id\":\"slot-wed\",\"from\":\"%s\"},{\"id\":\"slot-fri\",\"from\":\"%s\"}]}"
                .formatted(wednesday, friday);
    }

    private void needs(List<String> gone) {
        checkinRepository.save(Checkin.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .rawInputText("stub")
                .parsedDelta(new CheckinDelta(List.of(), List.of(), gone))
                .receivedAt(Instant.now())
                .build());
    }

    /** A past confirmed order on a Friday, which is what makes Friday this household's habit. */
    private void pastOrderOn(DayOfWeek day) {
        Instant confirmedAt = LocalDate.now(KYIV)
                .with(TemporalAdjusters.previous(day))
                .atTime(18, 0)
                .atZone(KYIV)
                .toInstant();
        customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(OrderType.INITIAL)
                .items(List.of())
                .status(OrderStatus.CONFIRMED)
                .silpoCartId("cart-old")
                .createdAt(confirmedAt)
                .confirmedAt(confirmedAt)
                .build());
    }

    private void present() {
        DeltaOrder order = reorderService.buildScheduledDeltaOrder(user.getId());
        reorderConfirmationService.present(user, order);
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

    private CustomerOrder draft() {
        return customerOrderRepository
                .findByUserIdAndStatus(user.getId(), OrderStatus.DRAFT)
                .getFirst();
    }

    private int trustCounter() {
        return trustLevelRepository.findByUserId(user.getId()).orElseThrow().getConsecutiveUneditedConfirmations();
    }

    @Test
    void picksTheSlotOnTheHouseholdsUsualDay() {
        needs(List.of("Молоко"));
        pastOrderOn(DayOfWeek.FRIDAY);

        present();

        assertThat(draft().getDeliverySlot()).isEqualTo("slot-fri");
    }

    @Test
    void fallsBackToTheEarliestSlotWithNoHistoryToGoOn() {
        needs(List.of("Молоко"));

        present();

        assertThat(draft().getDeliverySlot()).isEqualTo("slot-wed");
    }

    @Test
    void chooseSlotPrefersTheEarliestWhenNothingMatchesTheHabit() {
        OfferedSlot later = new OfferedSlot("later", "later", Instant.now().plusSeconds(7200));
        OfferedSlot sooner = new OfferedSlot("sooner", "sooner", Instant.now().plusSeconds(3600));

        assertThat(reorderConfirmationService.chooseSlot(user.getId(), List.of(later, sooner)))
                .isEqualTo(sooner);
    }

    @Test
    void confirmingWithNoEditsLeavesTheBaselineAloneAndCountsTowardsTrust() throws Exception {
        needs(List.of("Молоко"));
        present();
        UUID baselineBefore = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow()
                .getId();

        tapButton(1, ReorderMessageService.CALLBACK_CONFIRM);

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CONFIRMED))
                .hasSize(1);
        assertThat(baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(user.getId())
                        .orElseThrow()
                        .getId())
                .isEqualTo(baselineBefore);
        assertThat(trustCounter()).isEqualTo(1);
        assertThat(lastMessageText()).contains("Підтвердив");
    }

    @Test
    void refusingASubstituteMakesTheOrderTheNewBaselineAndResetsTrust() throws Exception {
        needs(List.of("Молоко", "Хліб"));
        present();
        UUID baselineBefore = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow()
                .getId();

        tapButton(1, ReorderMessageService.CALLBACK_REJECT_PREFIX + "0");
        tapButton(2, ReorderMessageService.CALLBACK_CONFIRM);

        BaselineBasket current = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow();
        assertThat(current.getId()).isNotEqualTo(baselineBefore);
        // The superseded snapshot is kept, not deleted.
        assertThat(baselineBasketRepository.findByUserIdOrderByConfirmedAtDesc(user.getId()))
                .hasSize(2);
        assertThat(trustCounter()).isZero();
    }

    @Test
    void acceptingASubstituteAddsItToTheCartAndIsNotAnEdit() throws Exception {
        needs(List.of("Молоко", "Хліб"));
        present();
        UUID baselineBefore = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow()
                .getId();

        tapButton(1, ReorderMessageService.CALLBACK_ACCEPT_PREFIX + "0");
        tapButton(2, ReorderMessageService.CALLBACK_CONFIRM);

        JsonNode lastAdd =
                MCP.callArguments("silpo_add_or_update_cart_products").getLast();
        assertThat(lastAdd.path("products").get(0).path("productId").asText()).isEqualTo("p-77");
        assertThat(baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(user.getId())
                        .orElseThrow()
                        .getId())
                .isEqualTo(baselineBefore);
        assertThat(trustCounter()).isEqualTo(1);
    }

    @Test
    void bookedSlotIsSentToSilpoOnConfirm() throws Exception {
        needs(List.of("Молоко"));
        present();

        tapButton(1, ReorderMessageService.CALLBACK_CONFIRM);

        JsonNode update = MCP.callArguments("silpo_update_shopping_cart").getFirst();
        assertThat(update.path("timeslot").asText()).isEqualTo("slot-wed");
        assertThat(draft2()).isEmpty();
    }

    private List<CustomerOrder> draft2() {
        return customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.DRAFT);
    }

    @Test
    void aDifferentSlotCanBeChosenBeforeConfirming() throws Exception {
        needs(List.of("Молоко"));
        present();

        tapButton(1, ReorderMessageService.CALLBACK_SLOT_MENU);
        tapButton(2, ReorderMessageService.CALLBACK_SLOT_PREFIX + "1");
        tapButton(3, ReorderMessageService.CALLBACK_CONFIRM);

        JsonNode update = MCP.callArguments("silpo_update_shopping_cart").getFirst();
        assertThat(update.path("timeslot").asText()).isEqualTo("slot-fri");
        // Changing when the food arrives is not an edit of what is in the basket.
        assertThat(trustCounter()).isEqualTo(1);
    }

    @Test
    void cancellingTouchesNeitherBaselineNorTrust() throws Exception {
        needs(List.of("Молоко"));
        present();

        tapButton(1, ReorderMessageService.CALLBACK_CANCEL);

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CANCELLED))
                .hasSize(1);
        assertThat(baselineBasketRepository.findByUserIdOrderByConfirmedAtDesc(user.getId()))
                .hasSize(1);
        assertThat(trustLevelRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void aSecondConfirmTapChangesNothing() throws Exception {
        needs(List.of("Молоко"));
        present();

        tapButton(1, ReorderMessageService.CALLBACK_CONFIRM);
        int messagesAfterFirst = TELEGRAM.sentMessages().size();
        tapButton(2, ReorderMessageService.CALLBACK_CONFIRM);

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CONFIRMED))
                .hasSize(1);
        assertThat(trustCounter()).isEqualTo(1);
        assertThat(TELEGRAM.sentMessages()).hasSize(messagesAfterFirst);
    }

    @Test
    void theSavingsFigureFromTheDeltaOrderIsWhatTheUserSees() {
        needs(List.of("Молоко"));
        MCP.respondToTool(
                "silpo_get_promotions",
                "{\"promotions\":[{\"name\":\"Молоко\",\"productId\":\"p-1\",\"price\":30,\"oldPrice\":38}]}");

        present();

        // Two litres, eight hryvnia off each.
        assertThat(lastMessageText()).contains("16.00");
    }

    @Test
    void nothingNeededIsSaidPlainlyRatherThanShownAsAnEmptyOrder() {
        needs(List.of());

        present();

        assertThat(lastMessageText()).contains("нічого докуповувати");
        assertThat(customerOrderRepository.findAll()).isEmpty();
    }
}
