package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.Checkin;
import com.silporestockai.entity.InventoryTrend;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CheckinDelta;
import com.silporestockai.model.DeltaOrder;
import com.silporestockai.model.OrderType;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.CheckinRepository;
import com.silporestockai.repository.InventoryTrendRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ReorderService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubMcpServer;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("a reorder is the smallest cart that fixes the fridge, not the weekly list again")
class ReorderIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 9901L;
    private static final StubMcpServer MCP = startMcp();

    @Autowired
    private ReorderService reorderService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private CheckinRepository checkinRepository;

    @Autowired
    private InventoryTrendRepository inventoryTrendRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private UUID userId;

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products",
                    "silpo_get_promotions",
                    "silpo_get_replacements"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
    }

    @AfterAll
    static void stopStub() {
        MCP.close();
    }

    @BeforeEach
    void clean() {
        MCP.reset();
        inventoryTrendRepository.deleteAll();
        checkinRepository.deleteAll();
        baselineBasketRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();

        User user = userAccountService.findOrCreate(CHAT_ID);
        userId = user.getId();
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(userId)
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .items(List.of(
                        new BasketItem("p-1", "Молоко", "л", new BigDecimal("2"), new BigDecimal("38")),
                        new BasketItem("p-2", "Хліб", "шт", BigDecimal.ONE, new BigDecimal("25")),
                        new BasketItem("p-9", "Кіноа", "кг", BigDecimal.ONE, new BigDecimal("120"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
        scriptCart();
    }

    /** Steps 1, 2 and 5 of the documented sequence, plus an empty promotions answer. */
    private void scriptCart() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-9\"}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-9","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-1","name":"Молоко 2.5%","unit":"л","quantity":2,"price":38}],\
                "total":76,"validations":[]}""");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_promotions", "{\"promotions\":[]}");
        MCP.respondToTool(
                "silpo_find_products_batch",
                "{\"products\":[{\"name\":\"Молоко\",\"productId\":\"p-1\",\"branchId\":\"branch-7\"}]}");
    }

    private void needs(List<String> runningLow, List<String> gone) {
        checkinRepository.save(Checkin.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .rawInputText("stub")
                .parsedDelta(new CheckinDelta(List.of(), runningLow, gone))
                .receivedAt(Instant.now())
                .build());
    }

    private void neverEaten(String itemName) {
        inventoryTrendRepository.save(InventoryTrend.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .itemName(itemName)
                .consecutiveUntouchedCycles(5)
                .lastUpdated(Instant.now())
                .build());
    }

    private static List<String> searchedNames(JsonNode arguments) {
        return arguments.path("items").findValuesAsText("name");
    }

    @Test
    void searchesOnlyForWhatRanOut() {
        needs(List.of(), List.of("Молоко"));

        DeltaOrder order = reorderService.buildScheduledDeltaOrder(userId);

        assertThat(searchedNames(MCP.callArguments("silpo_find_products_batch").getFirst()))
                .containsExactly("Молоко");
        assertThat(order.reordered()).containsExactly("Молоко");
        assertThat(order.type()).isEqualTo(OrderType.SCHEDULED_REORDER);
    }

    @Test
    void neverReordersSomethingTheHouseholdDoesNotEat() {
        needs(List.of("Кіноа"), List.of("Молоко"));
        neverEaten("Кіноа");

        DeltaOrder order = reorderService.buildScheduledDeltaOrder(userId);

        assertThat(searchedNames(MCP.callArguments("silpo_find_products_batch").getFirst()))
                .doesNotContain("Кіноа");
        assertThat(order.excluded()).contains("Кіноа");
    }

    @Test
    void takesTheAmountFromTheBaselineRatherThanGuessing() {
        needs(List.of(), List.of("Молоко"));

        reorderService.buildScheduledDeltaOrder(userId);

        JsonNode search = MCP.callArguments("silpo_find_products_batch").getFirst();
        assertThat(search.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void offersSubstitutesForWhatDidNotMakeItIntoTheCart() {
        needs(List.of(), List.of("Молоко", "Хліб"));
        MCP.respondToTool(
                "silpo_get_replacements",
                "{\"replacements\":[{\"productId\":\"p-77\",\"name\":\"Хліб житній\",\"price\":27}]}");

        DeltaOrder order = reorderService.buildScheduledDeltaOrder(userId);

        // The cart came back with milk only, so bread is a question rather than a failed order.
        assertThat(order.reordered()).containsExactly("Молоко");
        assertThat(order.pendingReplacements()).hasSize(1);
        assertThat(order.pendingReplacements().getFirst().requestedName()).isEqualTo("Хліб");
        assertThat(order.pendingReplacements().getFirst().options())
                .extracting(option -> option.name())
                .containsExactly("Хліб житній");
    }

    @Test
    void prefersThePromotedVariantAndSaysWhatThatSaved() {
        needs(List.of(), List.of("Молоко"));
        MCP.respondToTool(
                "silpo_get_promotions",
                "{\"promotions\":[{\"name\":\"Молоко\",\"productId\":\"promo-1\",\"price\":30,\"oldPrice\":38}]}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-9","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"promo-1","name":"Молоко 2.5%","unit":"л","quantity":2,"price":30}],\
                "total":60,"validations":[]}""");

        DeltaOrder order = reorderService.buildScheduledDeltaOrder(userId);

        JsonNode added = MCP.callArguments("silpo_add_or_update_cart_products").getFirst();
        assertThat(added.path("products").get(0).path("productId").asText()).isEqualTo("promo-1");
        // Two litres, eight hryvnia off each.
        assertThat(order.estimatedSavings()).isEqualByComparingTo("16");
        assertThat(order.reordered()).containsExactly("Молоко");
    }

    @Test
    void aBrokenPromotionsCallStillProducesAnOrder() {
        needs(List.of(), List.of("Молоко"));
        MCP.failTool("silpo_get_promotions");

        DeltaOrder order = reorderService.buildScheduledDeltaOrder(userId);

        assertThat(order.reordered()).containsExactly("Молоко");
        assertThat(order.estimatedSavings()).isEqualByComparingTo("0");
    }

    @Test
    void bothEntryPointsProduceTheSameShape() {
        needs(List.of(), List.of("Молоко"));

        DeltaOrder scheduled = reorderService.buildScheduledDeltaOrder(userId);
        MCP.reset();
        scriptCart();
        DeltaOrder triggered = reorderService.buildTriggeredDeltaOrder(userId, "Молоко");

        assertThat(triggered.reordered()).isEqualTo(scheduled.reordered());
        assertThat(triggered.cart().cartId()).isEqualTo(scheduled.cart().cartId());
        assertThat(scheduled.type()).isEqualTo(OrderType.SCHEDULED_REORDER);
        assertThat(scheduled.triggerItem()).isNull();
        assertThat(triggered.type()).isEqualTo(OrderType.AD_HOC);
        assertThat(triggered.triggerItem()).isEqualTo("Молоко");
    }

    @Test
    void aTriggerItemIsOrderedEvenWhenTheLastCheckinNeverMentionedIt() {
        needs(List.of(), List.of());

        DeltaOrder order = reorderService.buildTriggeredDeltaOrder(userId, "Молоко");

        assertThat(order.reordered()).containsExactly("Молоко");
    }

    @Test
    void nothingNeededMeansNoCartAndNoCalls() {
        needs(List.of(), List.of());

        DeltaOrder order = reorderService.buildScheduledDeltaOrder(userId);

        assertThat(order.isEmpty()).isTrue();
        assertThat(order.cart()).isNull();
        assertThat(MCP.calledTools()).isEmpty();
    }
}
