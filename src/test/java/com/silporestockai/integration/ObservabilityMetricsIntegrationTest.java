package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.entity.PartnerPromotionEvent;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.PartnerPromotionEventType;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.model.PromotionType;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ObservabilityService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Task 54: the gauges are registered, the endpoint serves them, and the numbers are the ones in the database.
 *
 * <p>This is the test that catches the failure the dashboard cannot survive — a meter that was never registered, or
 * one whose Prometheus name is not what the committed PromQL asks for. Every assertion below names a series a panel
 * queries by that exact string.
 *
 * <p>{@code refresh()} is called directly rather than waited for: {@code application-test.yml} pins the interval to an
 * hour precisely so no schedule fires under test.
 */
class ObservabilityMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObservabilityService observabilityService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Autowired
    private PartnerPromotionRepository promotionRepository;

    @Autowired
    private PartnerPromotionEventRepository promotionEventRepository;

    @Autowired
    private CategoryResolutionLogRepository categoryResolutionLogRepository;

    @BeforeEach
    void clearOrders() {
        categoryResolutionLogRepository.deleteAll();
        promotionEventRepository.deleteAll();
        promotionRepository.deleteAll();
        customerOrderRepository.deleteAll();
    }

    @Test
    void servesTheDatabaseDerivedGaugesOnTheActuatorEndpoint() throws Exception {
        User user = newUser(4_054_001L);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
        confirmedOrder(user.getId(), OrderType.INITIAL, new BigDecimal("812.40"), new BigDecimal("799.90"), 2);

        observabilityService.refresh();
        String body = scrape();

        assertThat(body).contains("komora_users_registered");
        assertThat(body).contains("komora_users_onboarded");
        assertThat(body).contains("komora_users_ordered");
        assertThat(body).contains("komora_orders_gmv_uah");
        assertThat(body).contains("komora_orders_goods_uah");
        assertThat(body).contains("komora_orders_items");
        assertThat(body).contains("komora_orders_value_missing");
        assertThat(body).contains("komora_users_active{");
        // The type tag the "orders by type" panel splits on has to be the enum name, not an ordinal.
        assertThat(body).contains("komora_orders_confirmed{").contains("type=\"INITIAL\"");
        assertThat(valueOf(body, "komora_orders_gmv_uah", "type=\"INITIAL\"")).isEqualTo(812.40);
        assertThat(valueOf(body, "komora_orders_goods_uah", "type=\"INITIAL\"")).isEqualTo(799.90);
        assertThat(valueOf(body, "komora_orders_confirmed", "type=\"INITIAL\"")).isEqualTo(1.0);
    }

    @Test
    void gmvSumsOnlyConfirmedOrdersThatRecordedATotal() throws Exception {
        User user = newUser(4_054_002L);
        confirmedOrder(user.getId(), OrderType.INITIAL, new BigDecimal("500.00"), new BigDecimal("500.00"), 1);
        confirmedOrder(user.getId(), OrderType.AD_HOC, new BigDecimal("250.50"), new BigDecimal("250.50"), 1);
        // A row from before task 54's columns existed: counted as an order, excluded from the money, and reported
        // as missing so the dashboard shows the coverage of its own GMV number.
        confirmedOrder(user.getId(), OrderType.AD_HOC, null, null, 3);
        // A draft is not revenue.
        customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(OrderType.INITIAL)
                .items(List.of(item(new BigDecimal("9999.00"))))
                .status(OrderStatus.DRAFT)
                .createdAt(Instant.now())
                .build());

        observabilityService.refresh();
        String body = scrape();

        assertThat(valueOf(body, "komora_orders_gmv_uah", "type=\"INITIAL\"")).isEqualTo(500.00);
        assertThat(valueOf(body, "komora_orders_gmv_uah", "type=\"AD_HOC\"")).isEqualTo(250.50);
        assertThat(valueOf(body, "komora_orders_confirmed", "type=\"AD_HOC\"")).isEqualTo(2.0);
        assertThat(valueOf(body, "komora_orders_value_missing", "type=\"AD_HOC\""))
                .isEqualTo(1.0);
        assertThat(valueOf(body, "komora_orders_value_missing", "type=\"INITIAL\""))
                .isEqualTo(0.0);
        // The funnel's numerator is households, not orders. As a derived query this counted distinct orders and
        // the live dashboard read «перше замовлення: 4» for one household — a 400 % conversion on the pitch screen.
        assertThat(valueOf(body, "komora_users_ordered", "")).isEqualTo(1.0);
    }

    @Test
    void callSiteCountersAndTimersAreExposedOnceSomethingHappens() throws Exception {
        observabilityService.recordFailureMessage("recovery", "silpo");
        observabilityService.recordCartLines(7, 2);
        observabilityService.recordMinimumOrder(true);
        observabilityService.recordTopUp("applied");
        observabilityService.recordIntent("routed");
        observabilityService.recordCartBuild("ok", java.time.Duration.ofSeconds(3));
        observabilityService.recordConfirmedOrder(OrderType.SCHEDULED_REORDER, new BigDecimal("640.00"), 11);

        String body = scrape();

        assertThat(body).contains("komora_failure_message_total").contains("kind=\"silpo\"");
        assertThat(body).contains("komora_cart_lines_total").contains("result=\"unresolved\"");
        assertThat(body).contains("komora_cart_minimum_total").contains("result=\"below\"");
        assertThat(body).contains("komora_cart_topup_total").contains("result=\"applied\"");
        assertThat(body).contains("komora_intent_classified_total");
        assertThat(body).contains("komora_orders_confirmations_total");
        assertThat(body).contains("komora_cart_value_uah");
        assertThat(body).contains("komora_cart_size");
        // The SLO buckets configured in application.yml are what the p95 panels read.
        assertThat(body).contains("komora_cart_build_seconds_bucket");
    }

    @Test
    void publishesShareLiftAndAttributedRevenuePerPlacementAndPerPool() throws Exception {
        User user = newUser(4_064_001L);
        PartnerPromotion own = promotionRepository.save(PartnerPromotion.builder()
                .id(UUID.randomUUID())
                .partnerName("Сільпо власна марка «Премія»")
                .categoryOrQuery("чай")
                .silpoProductId("p-tea-own")
                .productName("Чай «Премія» чорний 100г")
                .priorityWeight(100)
                .promotionType(PromotionType.OWN_BRAND_MARGIN_BOOST)
                .status(PartnerPromotionStatus.ACTIVE)
                .createdAt(Instant.now())
                .build());
        resolution(user.getId(), "чай", "p-tea-own", own.getId(), null);
        resolution(user.getId(), "чай", "p-tea-other", null, null);
        UUID orderId = UUID.randomUUID();
        customerOrderRepository.save(CustomerOrder.builder()
                .id(orderId)
                .userId(user.getId())
                .type(OrderType.SCHEDULED_REORDER)
                .items(List.of(
                        new BasketItem("p-tea-own", "Чай «Премія»", "шт", BigDecimal.ONE, new BigDecimal("62.50"))))
                .status(OrderStatus.CONFIRMED)
                .createdAt(Instant.now())
                .confirmedAt(Instant.now())
                .total(new BigDecimal("820.00"))
                .build());
        promotionEventRepository.save(PartnerPromotionEvent.builder()
                .id(UUID.randomUUID())
                .promotionId(own.getId())
                .userId(user.getId())
                .orderId(orderId)
                .eventType(PartnerPromotionEventType.CONFIRMED_ORDER)
                .occurredAt(Instant.now())
                .build());

        observabilityService.refresh();
        String body = scrape();

        assertThat(body).contains("komora_promotion_share{").contains("type=\"OWN_BRAND_MARGIN_BOOST\"");
        assertThat(valueOf(body, "komora_promotion_share", "category=\"чай\"")).isEqualTo(0.5);
        assertThat(valueOf(body, "komora_promotion_revenue_uah", "category=\"чай\""))
                .isEqualTo(62.50);
        assertThat(valueOf(body, "komora_promotion_share_overall", "type=\"ALL\""))
                .isEqualTo(0.5);
        assertThat(valueOf(body, "komora_promotion_revenue_overall_uah", "type=\"ALL\""))
                .isEqualTo(62.50);
        assertThat(valueOf(body, "komora_promotion_categories", "type=\"OWN_BRAND_MARGIN_BOOST\""))
                .isEqualTo(1.0);
        // The events gauge splits by pool and category so the funnel panels can be filtered per section.
        assertThat(body).contains("komora_promotion_events{").contains("event=\"CONFIRMED_ORDER\"");
        // Two resolutions and no candidate counts worth measuring: no baseline, therefore no lift series at all.
        // A zero here would read as «this placement achieved nothing» rather than «we do not know».
        assertThat(body).doesNotContain("komora_promotion_lift{");
    }

    private void resolution(UUID userId, String line, String productId, UUID promotionId, Integer candidates) {
        categoryResolutionLogRepository.save(CategoryResolutionLog.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .lineName(line)
                .resolvedProductId(productId)
                .resolvedProductName(productId)
                .promotionId(promotionId)
                .candidateCount(candidates)
                .occurredAt(Instant.now())
                .build());
    }

    private String scrape() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** Pulls one sample's value out of the exposition format, matching on the metric name and one tag. */
    private static double valueOf(String body, String metric, String tag) {
        return body.lines()
                .filter(line -> line.startsWith(metric + "{") && line.contains(tag))
                .map(line -> line.substring(line.lastIndexOf(' ') + 1))
                .mapToDouble(Double::parseDouble)
                .sum();
    }

    private User newUser(long chatId) {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .telegramChatId(chatId)
                .createdAt(Instant.now().minus(2, ChronoUnit.DAYS))
                .build());
    }

    private void confirmedOrder(UUID userId, OrderType type, BigDecimal total, BigDecimal goods, int lines) {
        customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .type(type)
                .items(java.util.stream.IntStream.range(0, lines)
                        .mapToObj(i -> item(new BigDecimal("100.00")))
                        .toList())
                .status(OrderStatus.CONFIRMED)
                .total(total)
                .goodsTotal(goods)
                .savings(BigDecimal.ZERO)
                .unresolvedCount(0)
                .createdAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .confirmedAt(Instant.now())
                .build());
    }

    private static BasketItem item(BigDecimal price) {
        return new BasketItem(UUID.randomUUID().toString(), "молоко", "шт", BigDecimal.ONE, price);
    }
}
