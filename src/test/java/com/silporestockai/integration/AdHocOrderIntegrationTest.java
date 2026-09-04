package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.AdHocOrderService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.telegram.CartMessageService;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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

@DisplayName("an ad-hoc order is a small discounted-snacks cart that never touches the weekly cycle")
class AdHocOrderIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "2020:stub-bot-token";
    private static final long CHAT_ID = 13701L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdHocOrderService adHocOrderService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

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
                    "silpo_get_promotions"));
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
                .items(List.of(new BasketItem("p-1", "Молоко", "л", new BigDecimal("2"), new BigDecimal("38"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
        scriptSilpo();
    }

    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-a\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-a","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-90","name":"Чіпси Lays","unit":"шт","quantity":1,"price":45}],\
                "total":45,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-a",\
                "checkoutMobileLink":"silpo://checkout/cart-a"}""");
        // Mixed bag: two snacks on promo, one non-snack promo (should be ignored), one snack-named item with
        // no old price (not actually a discount, should be ignored).
        MCP.respondToTool("silpo_get_promotions", """
                {"promotions":[\
                {"name":"Чіпси Lays соло","productId":"p-90","price":45,"oldPrice":65},\
                {"name":"Шоколад Milka","productId":"p-91","price":30,"oldPrice":50},\
                {"name":"Пральний порошок Persil","productId":"p-92","price":100,"oldPrice":140},\
                {"name":"Печиво Oreo","productId":"p-93","price":25}]}""");
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

    @Test
    void onlyActuallyDiscountedSnacksMakeTheCart() {
        adHocOrderService.buildAdHocOrder(user, "вечір п'ятниці", Instant.now());

        CustomerOrder draft = customerOrderRepository
                .findByUserIdAndStatus(user.getId(), OrderStatus.DRAFT)
                .getFirst();
        assertThat(draft.getType()).isEqualTo(OrderType.AD_HOC);

        List<String> addedProductIds = new ArrayList<>();
        MCP.callArguments("silpo_add_or_update_cart_products")
                .getFirst()
                .path("products")
                .forEach(p -> addedProductIds.add(p.path("productId").asText()));
        assertThat(addedProductIds).containsExactlyInAnyOrder("p-90", "p-91");
        // p-92 (Persil) is not a snack; p-93 (Oreo) has no oldPrice, so it is not actually discounted.
        assertThat(addedProductIds).doesNotContain("p-92", "p-93");
    }

    @Test
    void aPrefaceMessageNamesTheSavings() {
        adHocOrderService.buildAdHocOrder(user, "вечір п'ятниці", Instant.now());

        assertThat(TELEGRAM.sentMessages())
                .anySatisfy(message ->
                        assertThat(message.path("text").asText()).contains("40").contains("вечір п'ятниці"));
    }

    @Test
    void confirmingLeavesTheBaselineAndCheckinCycleUntouched() throws Exception {
        UUID baselineBefore = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow()
                .getId();

        adHocOrderService.buildAdHocOrder(user, "вечір п'ятниці", Instant.now());
        tapButton(1, CartMessageService.CALLBACK_CONFIRM);

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CONFIRMED))
                .singleElement()
                .satisfies(order -> assertThat(order.getType()).isEqualTo(OrderType.AD_HOC));
        assertThat(baselineBasketRepository.findByUserIdOrderByConfirmedAtDesc(user.getId()))
                .hasSize(1);
        assertThat(baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(user.getId())
                        .orElseThrow()
                        .getId())
                .isEqualTo(baselineBefore);
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("https://silpo.ua/checkout/cart-a");
    }

    @Test
    void noActivePromotionsMeansNoOrderAndAClearMessageInstead() {
        MCP.respondToTool("silpo_get_promotions", "{\"promotions\":[]}");

        adHocOrderService.buildAdHocOrder(user, "вечір п'ятниці", Instant.now());

        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.DRAFT))
                .isEmpty();
        assertThat(TELEGRAM.sentMessages()).isNotEmpty();
    }
}
