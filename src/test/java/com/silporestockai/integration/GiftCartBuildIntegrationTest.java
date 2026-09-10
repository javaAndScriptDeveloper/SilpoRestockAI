package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.GiftOrder;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.GiftOrderStatus;
import com.silporestockai.model.GiftResolution;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.GiftOrderRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.GiftCartBuildingService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gift cart, and the one thing about it that is not negotiable: the address is set before a single product is
 * searched for. A branch travels with the address, and a product added before the move is invalidated by it.
 */
@DisplayName("a gift cart is built against the friend's branch, address first")
class GiftCartBuildIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "8181:stub-bot-token";
    private static final long SENDER_CHAT = 9601L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private GiftCartBuildingService giftCartBuildingService;

    @Autowired
    private GiftOrderRepository giftOrderRepository;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private User sender;

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
                    "silpo_find_address",
                    "silpo_get_available_delivery_types",
                    "silpo_get_time_slots",
                    "silpo_update_shopping_cart",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products"));
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

    private static final String GIFT_LINES = """
            {"items":[{"name":"Кава мелена","quantity":1,"unit":"шт","category":"Напої"}]}""";

    private static final String MATCH_THE_COFFEE = """
            {"choices":[{"lineIndex":0,"candidateIndex":0,"reason":"кава"}]}""";

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
        CLAUDE.reset();
        MCP.reset();
        giftOrderRepository.deleteAll();
        customerOrderRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();

        sender = userAccountService.findOrCreate(SENDER_CHAT, "andrii");
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(sender.getId())
                .householdSize(2)
                .onlyUaProducer(false)
                .build());
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(sender.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        scriptSilpo();
        // The theme becomes shop lines, then the matcher picks the one candidate Silpo answered with.
        CLAUDE.respondWithTexts(GIFT_LINES, MATCH_THE_COFFEE);
    }

    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-gift\"}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-gift","branchId":"branch-home","companyId":"company-3","deliveryType":"DeliveryHome",\
                "timeslot":{"start":"2026-09-11T06:00:00+00:00","end":"2026-09-11T07:30:00+00:00"},\
                "address":{"addressType":"flat","city":"Київ","street":"Урлівська вулиця","latitude":"50.403186",\
                "longitude":"30.6239378"},\
                "shipments":[{"companyId":"company-3","branchId":"branch-home"}],\
                "items":[{"productId":"00000000-0000-4000-8000-0000000000c1","name":"Кава Lavazza","quantity":1,\
                "price":249,"weighted":false}],\
                "calculation":{"total":249,"productsTotal":249,"subDiscount":0},\
                "validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-gift",\
                "checkoutMobileLink":"silpo://checkout/cart-gift"}""");
        MCP.respondToTool("silpo_find_address", """
                {"addresses":[{"address":"Київ, вулиця Хрещатик, 22","city":"Київ","street":"вулиця Хрещатик",\
                "houseNumber":"22","district":"Центр","latitude":50.4498465,"longitude":30.5230925}]}""");
        MCP.respondToTool("silpo_get_available_delivery_types", """
                {"options":[{"deliveryType":"NovaPoshta","branchId":null},\
                {"deliveryType":"DeliveryHome","branchId":"branch-khreshchatyk"}]}""");
        MCP.respondToTool("silpo_get_time_slots", """
                {"timeSlots":[{"start":"2026-09-11T07:30:00+00:00","end":"2026-09-11T09:00:00+00:00",\
                "available":true}]}""");
        MCP.respondToTool("silpo_update_shopping_cart", "{\"success\":true}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_find_products_batch", """
                {"queries":[{"query":"кава мелена","products":[{"name":"Кава Lavazza",\
                "productId":"00000000-0000-4000-8000-0000000000c1","step":1,"displayRatio":"250г","price":249}]}]}""");
    }

    private GiftOrder resolvedGift() {
        return giftOrderRepository.save(GiftOrder.builder()
                .id(UUID.randomUUID())
                .senderUserId(sender.getId())
                .recipientUsername("olena")
                .status(GiftOrderStatus.RESOLVED)
                .resolution(GiftResolution.ASKED)
                .theme("щось до кави")
                .giftAddressText("Київ, вулиця Хрещатик, 22")
                .giftFlat("42")
                .giftPhone("+380671234567")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    @Test
    void pointsTheCartAtTheFriendBeforeAnythingIsSearchedFor() {
        giftCartBuildingService.build(sender, resolvedGift(), null);

        assertThat(MCP.calledTools())
                .containsSubsequence(
                        "silpo_find_address",
                        "silpo_get_available_delivery_types",
                        "silpo_get_time_slots",
                        "silpo_update_shopping_cart",
                        "silpo_find_products_batch");
    }

    @Test
    void sendsTheFriendsDoorAndNumberToSilpoOnTheBranchItResolved() {
        giftCartBuildingService.build(sender, resolvedGift(), null);

        var address = MCP.callArguments("silpo_update_shopping_cart").getFirst().path("address");
        assertThat(address.path("street").asText()).isEqualTo("вулиця Хрещатик");
        assertThat(address.path("flat").asText()).isEqualTo("42");
        assertThat(address.path("phone").asText()).isEqualTo("+380671234567");
        assertThat(MCP.callArguments("silpo_update_shopping_cart")
                        .getFirst()
                        .path("shipments")
                        .get(0)
                        .path("branchId")
                        .asText())
                .isEqualTo("branch-khreshchatyk");
    }

    @Test
    void remembersTheHouseholdsOwnDeliveryBlockAndTheCartItBorrowed() {
        GiftOrder gift = resolvedGift();

        giftCartBuildingService.build(sender, gift, null);

        GiftOrder stored = giftOrderRepository.findById(gift.getId()).orElseThrow();
        assertThat(stored.getOwnDelivery()).containsKey("address").containsKey("shipments");
        // The cart id is written in the same save as the snapshot, not at presentation: live, a gift Silpo
        // refused on its minimum-order rule never reached presentation, and the row was left naming no cart to
        // put anything back on.
        assertThat(stored.getSilpoCartId()).isEqualTo("cart-gift");
        assertThat(stored.getStatus()).isEqualTo(GiftOrderStatus.CART_PRESENTED);
    }

    @Test
    void theDraftOrderIsAGiftSoItNeverBecomesTheHouseholdsBaseline() {
        giftCartBuildingService.build(sender, resolvedGift(), null);

        assertThat(customerOrderRepository.findAll())
                .singleElement()
                .extracting(order -> order.getType(), order -> order.getStatus())
                .containsExactly(OrderType.GIFT, OrderStatus.DRAFT);
    }

    @Test
    void theSenderSeesTheFriendsNicknameAndNoAddress() {
        giftCartBuildingService.build(sender, resolvedGift(), null);

        String sent = TELEGRAM.sentMessages().stream()
                .map(message -> message.path("text").asText())
                .reduce("", (all, one) -> all + "\n" + one);
        assertThat(sent).contains("Подарунок для @olena").contains("Кава Lavazza");
        assertThat(sent).doesNotContain("Хрещатик").doesNotContain("+380671234567");
    }
}
