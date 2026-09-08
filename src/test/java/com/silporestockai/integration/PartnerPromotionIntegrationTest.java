package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.entity.PartnerPromotionEvent;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.DietType;
import com.silporestockai.model.OrderConfirmedEvent;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.PartnerPromotionEventType;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.model.PromotionType;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CartBuildingService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.telegram.CartMessageService;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("paid partner placement (task 46)")
class PartnerPromotionIntegrationTest extends AbstractIntegrationTest {

    private static final StubMcpServer MCP = startMcp();
    private static final String PARTNER_MILK_NAME = "Молоко Яготинське 2.5% 900г";
    private static final String PARTNER_MILK_ID = "p-milk-partner";

    @Autowired
    private CartBuildingService cartBuildingService;

    @Autowired
    private CartMessageService cartMessageService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    @Autowired
    private PartnerPromotionRepository promotionRepository;

    @Autowired
    private PartnerPromotionEventRepository eventRepository;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CategoryResolutionLogRepository logRepository;

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_clear_shopping_cart",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("komora.metrics.token", () -> "promo-token");
    }

    @AfterAll
    static void stopStub() {
        MCP.close();
    }

    @BeforeEach
    void clean() {
        MCP.reset();
        // Before the promotions: the log's foreign key points at the rows the next line deletes.
        logRepository.deleteAll();
        eventRepository.deleteAll();
        promotionRepository.deleteAll();
        customerOrderRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        scriptCart();
    }

    private UUID connectedUser(UserProfile profile) {
        User user = userAccountService.findOrCreate(4601L);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        if (profile != null) {
            profile.setUserId(user.getId());
            userProfileRepository.save(profile);
        }
        return user.getId();
    }

    private PartnerPromotion milkPromotion() {
        return promotionRepository.save(PartnerPromotion.builder()
                .id(UUID.randomUUID())
                .partnerName("Яготинське")
                .categoryOrQuery("молоко")
                .silpoProductId(PARTNER_MILK_ID)
                .productName(PARTNER_MILK_NAME)
                .priorityWeight(100)
                .status(PartnerPromotionStatus.ACTIVE)
                .createdAt(Instant.now())
                .build());
    }

    private static ShoppingListItem item(String name) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .quantity(BigDecimal.ONE)
                .unit("шт")
                .build();
    }

    private void scriptCart() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-p\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_clear_shopping_cart", "{\"ok\":true}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-p","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"%s","name":"%s","unit":"шт","quantity":1,"price":52.9},\
                {"productId":"p-2","name":"Гречка","unit":"кг","quantity":1,"price":48}],\
                "total":100.9,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-p",\
                "checkoutMobileLink":"silpo://checkout/cart-p"}""".formatted(PARTNER_MILK_ID, PARTNER_MILK_NAME));
    }

    /** The catalog answers both the household's word and the partner's exact product name. */
    private void catalogHasThePartnerMilk() {
        MCP.respondToTool(
                "silpo_find_products_batch", """
                {"queries":[\
                {"query":"молоко","products":[{"name":"Молоко Селянське 900г","productId":"p-milk-generic","branchId":"branch-7"}]},\
                {"query":"%s","products":[{"name":"%s","productId":"%s","branchId":"branch-7"}]},\
                {"query":"гречка","products":[{"name":"Гречка","productId":"p-2","branchId":"branch-7"}]}]}""".formatted(PARTNER_MILK_NAME, PARTNER_MILK_NAME, PARTNER_MILK_ID));
    }

    /** The partner's product is not returned for this branch right now — out of stock, delisted, whatever. */
    private void catalogLacksThePartnerMilk() {
        MCP.respondToTool("silpo_find_products_batch", """
                {"queries":[\
                {"query":"молоко","products":[{"name":"Молоко Селянське 900г","productId":"p-milk-generic","branchId":"branch-7"}]},\
                {"query":"%s","products":[]},\
                {"query":"гречка","products":[{"name":"Гречка","productId":"p-2","branchId":"branch-7"}]}]}""".formatted(PARTNER_MILK_NAME));
    }

    private List<String> addedProductIds() {
        JsonNode arguments =
                MCP.callArguments("silpo_add_or_update_cart_products").getLast();
        return arguments.path("products").findValues("productId").stream()
                .map(JsonNode::asText)
                .toList();
    }

    private List<String> searchedTerms() {
        return MCP.callArguments("silpo_find_products_batch").getLast().path("products").findValues("").stream()
                .map(JsonNode::asText)
                .toList();
    }

    @Test
    void thePartnersProductAnswersTheCategoryTheHouseholdAskedForAndTheFunnelIsLogged() {
        UUID userId = connectedUser(null);
        PartnerPromotion promotion = milkPromotion();
        catalogHasThePartnerMilk();

        CartSummary summary = cartBuildingService.buildCart(userId, List.of(item("молоко"), item("гречка")));

        assertThat(addedProductIds()).containsExactly(PARTNER_MILK_ID, "p-2");
        assertThat(summary.promotedProductIds()).containsExactly(PARTNER_MILK_ID);
        // The partner's exact name rode along in the same batch — no extra MCP call.
        assertThat(MCP.calledTools().stream()
                        .filter("silpo_find_products_batch"::equals)
                        .count())
                .isEqualTo(1);
        assertThat(MCP.callArguments("silpo_find_products_batch")
                        .getLast()
                        .path("products")
                        .toString())
                .contains("молоко")
                .contains(PARTNER_MILK_NAME);
        List<PartnerPromotionEvent> funnel = eventRepository.findByPromotionId(promotion.getId());
        assertThat(funnel)
                .extracting(PartnerPromotionEvent::getEventType)
                .containsExactly(PartnerPromotionEventType.IMPRESSION, PartnerPromotionEventType.ADDED_TO_CART);
        assertThat(funnel).allSatisfy(event -> assertThat(event.getUserId()).isEqualTo(userId));
        // Task 62: the placement is counted, never announced — the line reads like any other line.
        String text = cartMessageService.cartText(summary, null, OrderType.INITIAL);
        assertThat(text).contains(PARTNER_MILK_NAME).doesNotContain("★").doesNotContain("партнерськ");
    }

    @Test
    void fallsBackToTheOrdinaryMatchWhenThePartnersProductIsNotReturnedLive() {
        UUID userId = connectedUser(null);
        PartnerPromotion promotion = milkPromotion();
        catalogLacksThePartnerMilk();

        CartSummary summary = cartBuildingService.buildCart(userId, List.of(item("молоко"), item("гречка")));

        assertThat(addedProductIds()).containsExactly("p-milk-generic", "p-2");
        assertThat(summary.promotedProductIds()).isEmpty();
        assertThat(eventRepository.findByPromotionId(promotion.getId())).isEmpty();
    }

    @Test
    void aLactoseFreeHouseholdNeverSeesAMilkPlacementNorIsItEvenSearched() {
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .householdSize(2)
                .dietaryRestrictions(List.of("lactose"))
                .dietType(DietType.NONE)
                .build();
        UUID userId = connectedUser(profile);
        PartnerPromotion promotion = milkPromotion();
        catalogHasThePartnerMilk();

        cartBuildingService.buildCart(userId, List.of(item("молоко"), item("гречка")));

        assertThat(addedProductIds()).containsExactly("p-milk-generic", "p-2");
        assertThat(MCP.callArguments("silpo_find_products_batch")
                        .getLast()
                        .path("products")
                        .toString())
                .doesNotContain(PARTNER_MILK_NAME);
        assertThat(eventRepository.findByPromotionId(promotion.getId())).isEmpty();
    }

    @Test
    void aPlacementNeverAddsACategoryNobodyAskedFor() {
        UUID userId = connectedUser(null);
        PartnerPromotion promotion = milkPromotion();
        MCP.respondToTool("silpo_find_products_batch", """
                {"queries":[{"query":"гречка","products":[{"name":"Гречка","productId":"p-2","branchId":"branch-7"}]}]}""");

        cartBuildingService.buildCart(userId, List.of(item("гречка")));

        assertThat(addedProductIds()).containsExactly("p-2");
        assertThat(MCP.callArguments("silpo_find_products_batch")
                        .getLast()
                        .path("products")
                        .toString())
                .doesNotContain(PARTNER_MILK_NAME);
        assertThat(eventRepository.findByPromotionId(promotion.getId())).isEmpty();
    }

    @Test
    void aConfirmedOrderThatStillHoldsThePlacementClosesTheFunnelAndTheReportShowsIt() throws Exception {
        UUID userId = connectedUser(null);
        PartnerPromotion promotion = milkPromotion();
        catalogHasThePartnerMilk();
        cartBuildingService.buildCart(userId, List.of(item("молоко")));
        CustomerOrder order = customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .type(OrderType.INITIAL)
                .items(List.of(new BasketItem(
                        PARTNER_MILK_ID, PARTNER_MILK_NAME, "шт", BigDecimal.ONE, new BigDecimal("52.9"))))
                .status(OrderStatus.CONFIRMED)
                .createdAt(Instant.now())
                .confirmedAt(Instant.now())
                .build());

        events.publishEvent(new OrderConfirmedEvent(userId, order.getId(), null, "18:00", 1));

        assertThat(eventRepository.countByPromotionIdAndEventType(
                        promotion.getId(), PartnerPromotionEventType.CONFIRMED_ORDER))
                .isEqualTo(1);
        String report = mockMvc.perform(get("/internal/promotions/report").header("X-Metrics-Token", "promo-token"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(report)
                .contains("| Яготинське | молоко | " + PARTNER_MILK_NAME + " | ACTIVE | 1 | 1 | 1 | 100 % | 100 % |");
    }

    @Test
    void creatingAPlacementVerifiesTheProductAgainstTheCatalogFirst() throws Exception {
        UUID userId = connectedUser(null);
        catalogHasThePartnerMilk();

        String body = mockMvc.perform(post("/internal/promotions")
                        .header("X-Metrics-Token", "promo-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partnerName":"Яготинське","categoryOrQuery":"молоко",\
                                "productQuery":"%s","verifyAsUserId":"%s"}""".formatted(PARTNER_MILK_NAME, userId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .contains("\"silpoProductId\":\"" + PARTNER_MILK_ID + "\"")
                .contains("\"status\":\"ACTIVE\"");
        assertThat(promotionRepository.findAll()).hasSize(1);
        assertThat(promotionRepository.findAll().getFirst().getProductName()).isEqualTo(PARTNER_MILK_NAME);

        mockMvc.perform(post("/internal/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerName\":\"x\",\"categoryOrQuery\":\"y\",\"productQuery\":\"z\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("every resolved line is logged, promoted or not — that is the FSR denominator")
    void everyResolvedLineIsLogged() {
        UUID userId = connectedUser(null);
        PartnerPromotion promotion = milkPromotion();
        catalogHasThePartnerMilk();

        cartBuildingService.buildCart(userId, List.of(item("молоко"), item("гречка")));

        List<CategoryResolutionLog> rows = logRepository.findAll();
        assertThat(rows).hasSize(2);
        assertThat(rows)
                .filteredOn(row -> "молоко".equals(row.getLineName()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getPromotionId()).isEqualTo(promotion.getId());
                    assertThat(row.getResolvedProductId()).isEqualTo(PARTNER_MILK_ID);
                    assertThat(row.getUserId()).isEqualTo(userId);
                    assertThat(row.getCandidateCount()).isNotNull();
                });
        assertThat(rows)
                .filteredOn(row -> "гречка".equals(row.getLineName()))
                .singleElement()
                .satisfies(row -> assertThat(row.getPromotionId()).isNull());
    }

    @Test
    @DisplayName("an ordinary match is logged with no placement behind it")
    void anUnpromotedResolutionIsStillCounted() {
        UUID userId = connectedUser(null);
        milkPromotion();
        catalogLacksThePartnerMilk();

        cartBuildingService.buildCart(userId, List.of(item("молоко"), item("гречка")));

        assertThat(logRepository.findAll())
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.getPromotionId()).isNull());
    }

    @Test
    @DisplayName("a placement created before task 63 reads back as a paid partner placement")
    void aRowWithoutAPromotionTypeDefaultsToPaidPartner() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into partner_promotion
                    (id, partner_name, category_or_query, silpo_product_id, product_name,
                     priority_weight, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, now())
                """,
                id,
                "Яготинське",
                "молоко",
                PARTNER_MILK_ID,
                PARTNER_MILK_NAME,
                100,
                PartnerPromotionStatus.ACTIVE.name());

        PartnerPromotion stored = promotionRepository.findById(id).orElseThrow();

        assertThat(stored.getPromotionType()).isEqualTo(PromotionType.PAID_PARTNER);
    }

    @Test
    @DisplayName("an own-brand placement can be created and keeps its type")
    void anOwnBrandPlacementKeepsItsType() {
        PartnerPromotion stored = promotionRepository.save(PartnerPromotion.builder()
                .id(UUID.randomUUID())
                .partnerName("Сільпо власна марка")
                .categoryOrQuery("чай")
                .silpoProductId("p-tea-own")
                .productName("Чай «Премія» чорний")
                .priorityWeight(100)
                .promotionType(PromotionType.OWN_BRAND_MARGIN_BOOST)
                .status(PartnerPromotionStatus.ACTIVE)
                .createdAt(Instant.now())
                .build());

        assertThat(promotionRepository.findById(stored.getId()).orElseThrow().getPromotionType())
                .isEqualTo(PromotionType.OWN_BRAND_MARGIN_BOOST);
    }
}
