package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.CartSummary;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CartBuildingService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubMcpServer;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("the documented six-call cart sequence, against a stub MCP server")
class CartBuildingIntegrationTest extends AbstractIntegrationTest {

    private static final StubMcpServer MCP = startMcp();

    @Autowired
    private CartBuildingService cartBuildingService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products",
                    "silpo_get_my_delivery_addresses",
                    "silpo_get_available_delivery_types",
                    "silpo_list_branches",
                    "silpo_create_shopping_cart"));
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
        tokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** isConnected reads the database, so a connected guest is simulated by inserting a token row. */
    private UUID connectedUser(long chatId) {
        User user = userAccountService.findOrCreate(chatId);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user.getId();
    }

    private static ShoppingListItem item(String name, String quantity, String unit) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .quantity(new BigDecimal(quantity))
                .unit(unit)
                .build();
    }

    private void scriptCartTools() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-1\"}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3",\
                "deliveryType":"delivery","items":[]}""");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
    }

    private void scriptProductTools() {
        MCP.respondToTool("silpo_find_products_batch", """
                {"products":[\
                {"name":"цибуля","productId":"p-1","companyId":"company-3","branchId":"branch-7"},\
                {"name":"гречка","productId":"p-2","companyId":"company-3","branchId":"branch-7"}]}""");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
    }

    /** The verified cart of step 6. Overrides what {@link #scriptCartTools()} scripted for the same tool. */
    private void scriptVerifiedCart() {
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-1","name":"Цибуля","unit":"кг","quantity":0.5,"price":25.5},\
                {"productId":"p-2","name":"Гречка","unit":"кг","quantity":1,"price":48}],\
                "total":73.5,"validations":[],\
                "loyalty":{"bonusAvailable":120,"bonusRequested":null,"isEnabled":true},\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-1",\
                "checkoutMobileLink":"silpo://checkout/cart-1"}""");
    }

    @Test
    void readsTheCartAndItsBranchFromTheFirstTwoCalls() {
        UUID userId = connectedUser(8401L);
        scriptCartTools();

        CartContext context = cartBuildingService.getOrCreateCartContext(userId);

        assertThat(context.cartId()).isEqualTo("cart-1");
        assertThat(context.branchId()).isEqualTo("branch-7");
        assertThat(context.companyId()).isEqualTo("company-3");
        assertThat(context.deliveryType()).isEqualTo("delivery");
        assertThat(MCP.calledTools()).containsExactly("silpo_get_my_shopping_cart", "silpo_get_shopping_cart_by_id");
    }

    @Test
    void picksTheFirstOfferedTimeSlot() {
        UUID userId = connectedUser(8402L);
        scriptCartTools();
        CartContext context = cartBuildingService.getOrCreateCartContext(userId);

        String slot = cartBuildingService.validateTimeSlot(userId, context);

        assertThat(slot).isEqualTo("slot-1");
    }

    @Test
    void refusesToBuildACartThatCannotBeDelivered() {
        UUID userId = connectedUser(8403L);
        scriptCartTools();
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[]}");
        CartContext context = cartBuildingService.getOrCreateCartContext(userId);

        assertThatThrownBy(() -> cartBuildingService.validateTimeSlot(userId, context))
                .isInstanceOf(CartBuildException.class)
                .hasMessageContaining("time slot");
    }

    @Test
    void runsAllSixCallsInTheDocumentedOrder() {
        UUID userId = connectedUser(8404L);
        scriptCartTools();
        scriptProductTools();

        cartBuildingService.buildCart(userId, List.of(item("цибуля", "0.5", "кг")));

        assertThat(MCP.calledTools())
                .containsExactly(
                        "silpo_get_my_shopping_cart",
                        "silpo_get_shopping_cart_by_id",
                        "silpo_get_time_slots",
                        "silpo_find_products_batch",
                        "silpo_add_or_update_cart_products",
                        "silpo_get_shopping_cart_by_id");
    }

    @Test
    void chunksAtThirtyItemsPerSearchCall() {
        UUID userId = connectedUser(8405L);
        scriptCartTools();
        scriptProductTools();
        List<ShoppingListItem> items = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            items.add(item("товар-" + i, "1", "шт"));
        }

        cartBuildingService.buildCart(userId, items);

        assertThat(MCP.calledTools().stream()
                        .filter("silpo_find_products_batch"::equals)
                        .count())
                .isEqualTo(2);
    }

    @Test
    void reportsWhatSilpoCouldNotMatchInsteadOfDroppingIt() {
        UUID userId = connectedUser(8406L);
        scriptCartTools();
        scriptProductTools();
        scriptVerifiedCart();

        CartSummary summary =
                cartBuildingService.buildCart(userId, List.of(item("цибуля", "0.5", "кг"), item("трюфелі", "1", "кг")));

        assertThat(summary.unresolved()).containsExactly("трюфелі");
        assertThat(summary.items()).isNotEmpty();
    }

    @Test
    void surfacesAvailableBonusesWithoutSpendingThem() {
        UUID userId = connectedUser(8407L);
        scriptCartTools();
        scriptProductTools();
        scriptVerifiedCart();

        CartSummary summary = cartBuildingService.buildCart(userId, List.of(item("цибуля", "0.5", "кг")));

        assertThat(summary.deliverySlot()).isEqualTo("slot-1");
        assertThat(summary.bonusAvailable()).isEqualByComparingTo("120");
        assertThat(summary.bonusDecisionPending()).isTrue();
        assertThat(summary.total()).isEqualByComparingTo("73.5");
        assertThat(summary.checkoutWebLink()).contains("checkout");
        assertThat(summary.items()).hasSize(2);
    }

    @Test
    void addsNothingWhenThereIsNoDeliverableSlot() {
        UUID userId = connectedUser(8408L);
        scriptCartTools();
        scriptProductTools();
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[]}");

        assertThatThrownBy(() -> cartBuildingService.buildCart(userId, List.of(item("цибуля", "0.5", "кг"))))
                .isInstanceOf(CartBuildException.class);

        assertThat(MCP.calledTools()).doesNotContain("silpo_add_or_update_cart_products");
    }

    @Test
    void logsEveryToolCallAtInfoSoADemoCanBeRecorded() {
        UUID userId = connectedUser(8409L);
        scriptCartTools();
        scriptProductTools();
        scriptVerifiedCart();

        Logger logger = (Logger) LoggerFactory.getLogger(CartBuildingService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            cartBuildingService.buildCart(userId, List.of(item("цибуля", "0.5", "кг")));
        } finally {
            logger.detachAppender(appender);
        }

        List<String> info = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(info)
                .anyMatch(line -> line.contains("silpo_get_my_shopping_cart"))
                .anyMatch(line -> line.contains("silpo_get_shopping_cart_by_id"))
                .anyMatch(line -> line.contains("silpo_get_time_slots"))
                .anyMatch(line -> line.contains("silpo_find_products_batch"))
                .anyMatch(line -> line.contains("silpo_add_or_update_cart_products"));
    }

    /**
     * The exact failure a live account hit: {@code silpo_get_my_shopping_cart} answered without error, but the
     * response carried none of the key names in {@code McpResponses.CART_ID}. Guessing another key name blind would
     * only trade one wrong guess for another, so what has to survive is the raw response reaching the log — that is
     * what turns the next occurrence into a one-line fix instead of a repeat of this one.
     */
    @Test
    void logsTheRawResponseWhenNoCartIdCanBeFound() {
        UUID userId = connectedUser(8410L);
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"somethingElse\":\"cart-1\"}");

        Logger logger = (Logger) LoggerFactory.getLogger(CartBuildingService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> cartBuildingService.getOrCreateCartContext(userId))
                    .isInstanceOf(CartBuildException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .anyMatch(event -> event.getFormattedMessage().contains("somethingElse")
                        && event.getFormattedMessage().contains("cart-1"));
    }

    /**
     * {@code silpo_create_shopping_cart}'s own documented workflow: a saved address gives coordinates, coordinates
     * resolve a home-delivery option with its branch already attached, the branch's time slots give a window, and
     * only then does a cart exist to hand back to the ordinary six-step sequence.
     */
    @Test
    void createsACartFromASavedAddressWhenTheGuestHasNoneYet() {
        UUID userId = connectedUser(8412L);
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"success\":true,\"shoppingCartId\":null,\"exists\":false}");
        MCP.respondToTool(
                "silpo_get_my_delivery_addresses",
                """
                {"addresses":[{"addressType":"house","latitude":50.45,"longitude":30.52,\
                "city":"Київ","street":"Хрещатик","houseNumber":"1","district":"Шевченківський"}]}""");
        MCP.respondToTool(
                "silpo_get_available_delivery_types",
                "{\"deliveryTypes\":[{\"deliveryType\":\"DeliveryHome\",\"branchId\":\"branch-9\"}]}");
        MCP.respondToTool(
                "silpo_get_time_slots",
                """
                {"timeSlots":[{"id":"slot-1","start":"2026-09-03T10:00:00Z","end":"2026-09-03T12:00:00Z",\
                "available":true}]}""");
        MCP.respondToTool("silpo_create_shopping_cart", "{\"shoppingCartId\":\"cart-new\"}");
        MCP.respondToTool(
                "silpo_get_shopping_cart_by_id",
                """
                {"cartId":"cart-new","branchId":"branch-9","companyId":"company-1",\
                "deliveryType":"DeliveryHome","items":[]}""");

        CartContext context = cartBuildingService.getOrCreateCartContext(userId);

        assertThat(context.cartId()).isEqualTo("cart-new");
        assertThat(context.branchId()).isEqualTo("branch-9");
        assertThat(MCP.calledTools())
                .containsExactly(
                        "silpo_get_my_shopping_cart",
                        "silpo_get_my_delivery_addresses",
                        "silpo_get_available_delivery_types",
                        "silpo_get_time_slots",
                        "silpo_create_shopping_cart",
                        "silpo_get_shopping_cart_by_id");
        assertThat(MCP.callArguments("silpo_create_shopping_cart").getFirst().path("branchId").asText())
                .isEqualTo("branch-9");
    }

    /** {@code SelfPickup} comes back with no branch attached — resolving one is a documented extra step. */
    @Test
    void resolvesAPickupBranchWhenDeliveryTypeOffersNoneOfItsOwn() {
        UUID userId = connectedUser(8413L);
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"success\":true,\"shoppingCartId\":null,\"exists\":false}");
        MCP.respondToTool(
                "silpo_get_my_delivery_addresses",
                "{\"addresses\":[{\"addressType\":\"house\",\"latitude\":50.45,\"longitude\":30.52}]}");
        MCP.respondToTool(
                "silpo_get_available_delivery_types", "{\"deliveryTypes\":[{\"deliveryType\":\"SelfPickup\"}]}");
        MCP.respondToTool("silpo_list_branches", "{\"branches\":[{\"branchId\":\"pickup-branch-2\"}]}");
        MCP.respondToTool(
                "silpo_get_time_slots",
                """
                {"timeSlots":[{"id":"slot-1","start":"2026-09-03T10:00:00Z","end":"2026-09-03T12:00:00Z",\
                "available":true}]}""");
        MCP.respondToTool("silpo_create_shopping_cart", "{\"shoppingCartId\":\"cart-pickup\"}");
        MCP.respondToTool(
                "silpo_get_shopping_cart_by_id",
                """
                {"cartId":"cart-pickup","branchId":"pickup-branch-2","companyId":"company-1",\
                "deliveryType":"SelfPickup","items":[]}""");

        CartContext context = cartBuildingService.getOrCreateCartContext(userId);

        assertThat(context.cartId()).isEqualTo("cart-pickup");
        assertThat(MCP.calledTools()).contains("silpo_list_branches");
        assertThat(MCP.callArguments("silpo_get_time_slots").getFirst().path("branchId").asText())
                .isEqualTo("pickup-branch-2");
    }

    /** No saved address means nothing to create a cart from — a clear failure, not a guess at coordinates. */
    @Test
    void failsClearlyWhenTheGuestHasNoSavedAddressToCreateACartFrom() {
        UUID userId = connectedUser(8414L);
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"success\":true,\"shoppingCartId\":null,\"exists\":false}");
        MCP.respondToTool("silpo_get_my_delivery_addresses", "{\"addresses\":[]}");

        assertThatThrownBy(() -> cartBuildingService.getOrCreateCartContext(userId))
                .isInstanceOf(CartBuildException.class)
                .hasMessageContaining("no saved delivery address");
        assertThat(MCP.calledTools())
                .containsExactly("silpo_get_my_shopping_cart", "silpo_get_my_delivery_addresses");
    }
}
