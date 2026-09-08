package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.entity.PartnerPromotionEvent;
import com.silporestockai.entity.User;
import com.silporestockai.model.BaselineMethod;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.PartnerPromotionEventType;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.model.PromotionMetrics;
import com.silporestockai.model.PromotionRollup;
import com.silporestockai.model.PromotionType;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.PromotionMetricsService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("featured share, baseline and lift (task 63)")
class PromotionMetricsIntegrationTest extends AbstractIntegrationTest {

    private static final String OWN_TEA_ID = "p-tea-own";

    @Autowired
    private PromotionMetricsService metricsService;

    @Autowired
    private PartnerPromotionRepository promotionRepository;

    @Autowired
    private PartnerPromotionEventRepository eventRepository;

    @Autowired
    private CategoryResolutionLogRepository logRepository;

    @Autowired
    private CustomerOrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    private long chatId = 990_001L;

    @BeforeEach
    void clean() {
        logRepository.deleteAll();
        eventRepository.deleteAll();
        promotionRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();
    }

    private PartnerPromotion teaPromotion(PromotionType type) {
        return promotionRepository.save(PartnerPromotion.builder()
                .id(UUID.randomUUID())
                .partnerName(type == PromotionType.OWN_BRAND_MARGIN_BOOST ? "Сільпо власна марка" : "Ліптон")
                .categoryOrQuery("чай")
                .silpoProductId(OWN_TEA_ID)
                .productName("Чай «Премія» чорний 100г")
                .priorityWeight(100)
                .promotionType(type)
                .status(PartnerPromotionStatus.ACTIVE)
                .createdAt(Instant.now())
                .build());
    }

    private void resolution(String line, String productId, UUID promotionId, Integer candidates) {
        logRepository.save(CategoryResolutionLog.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .lineName(line)
                .resolvedProductId(productId)
                .resolvedProductName(productId)
                .promotionId(promotionId)
                .candidateCount(candidates)
                .occurredAt(Instant.now())
                .build());
    }

    /** A confirmed order and the placement event that ties it to a promotion, the way task 46 records it. */
    private UUID confirmedOrder(UUID promotionId, List<BasketItem> lines) {
        UUID orderId = UUID.randomUUID();
        UUID userId = userRepository
                .save(User.builder()
                        .id(UUID.randomUUID())
                        .telegramChatId(chatId++)
                        .silpoGuestId("guest-" + chatId)
                        .createdAt(Instant.now())
                        .build())
                .getId();
        orderRepository.save(CustomerOrder.builder()
                .id(orderId)
                .userId(userId)
                .type(OrderType.SCHEDULED_REORDER)
                .items(lines)
                .status(OrderStatus.CONFIRMED)
                .createdAt(Instant.now())
                .confirmedAt(Instant.now())
                .total(new BigDecimal("899.00"))
                .build());
        confirmedOrderEvent(promotionId, userId, orderId);
        return orderId;
    }

    private void confirmedOrderEvent(UUID promotionId, UUID userId, UUID orderId) {
        eventRepository.save(PartnerPromotionEvent.builder()
                .id(UUID.randomUUID())
                .promotionId(promotionId)
                .userId(userId)
                .orderId(orderId)
                .eventType(PartnerPromotionEventType.CONFIRMED_ORDER)
                .occurredAt(Instant.now())
                .build());
    }

    private static BasketItem line(String productId, String quantity, String price) {
        return new BasketItem(
                productId,
                productId,
                "шт",
                quantity == null ? null : new BigDecimal(quantity),
                price == null ? null : new BigDecimal(price));
    }

    @Test
    @DisplayName("attributed revenue is the promoted product's own lines, not the whole basket")
    void attributedRevenueCountsOnlyThePromotedProductLines() {
        PartnerPromotion tea = teaPromotion(PromotionType.OWN_BRAND_MARGIN_BOOST);
        confirmedOrder(tea.getId(), List.of(line(OWN_TEA_ID, "2", "47.90"), line("p-bread", "1", "31.50")));

        PromotionMetrics metrics = metricsService.metrics().getFirst();

        assertThat(metrics.attributedRevenue()).isEqualByComparingTo("95.80");
        assertThat(metrics.ordersMissingPrice()).isZero();
    }

    @Test
    @DisplayName("two events for the same order are one order's worth of money")
    void oneOrderIsAttributedOnce() {
        PartnerPromotion tea = teaPromotion(PromotionType.PAID_PARTNER);
        UUID orderId = confirmedOrder(tea.getId(), List.of(line(OWN_TEA_ID, "1", "47.90")));
        confirmedOrderEvent(tea.getId(), UUID.randomUUID(), orderId);

        assertThat(metricsService.metrics().getFirst().attributedRevenue()).isEqualByComparingTo("47.90");
    }

    @Test
    @DisplayName("a line with no stored price is counted as missing, never as zero hryvnia")
    void aPricelessLineIsAdmittedRatherThanAssumed() {
        PartnerPromotion tea = teaPromotion(PromotionType.PAID_PARTNER);
        confirmedOrder(tea.getId(), List.of(line(OWN_TEA_ID, "1", null)));

        PromotionMetrics metrics = metricsService.metrics().getFirst();

        assertThat(metrics.attributedRevenue()).isEqualByComparingTo("0.00");
        assertThat(metrics.ordersMissingPrice()).isEqualTo(1);
    }

    @Test
    @DisplayName("a placement nobody has ordered yet has no revenue rather than a guess")
    void aPlacementWithoutOrdersHasZeroRevenue() {
        teaPromotion(PromotionType.PAID_PARTNER);

        assertThat(metricsService.metrics().getFirst().attributedRevenue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("four featured tea lines out of five resolutions is a share of 80 %")
    void featuredShareRateIsCountedAgainstEveryResolutionInTheCategory() {
        PartnerPromotion tea = teaPromotion(PromotionType.OWN_BRAND_MARGIN_BOOST);
        resolution("чай", OWN_TEA_ID, tea.getId(), 3);
        resolution("чай зелений", OWN_TEA_ID, tea.getId(), 3);
        resolution("чай", OWN_TEA_ID, tea.getId(), 3);
        resolution("чай", OWN_TEA_ID, tea.getId(), 3);
        resolution("чай", "p-tea-other", null, 3);
        // A different category must not touch the tea denominator.
        resolution("молоко", "p-milk-generic", null, 4);

        PromotionMetrics metrics = metricsService.metrics().getFirst();

        assertThat(metrics.categoryResolutions()).isEqualTo(5);
        assertThat(metrics.featuredResolutions()).isEqualTo(4);
        assertThat(metrics.featuredShareRate()).isEqualTo(0.8);
        assertThat(metrics.baselineMethod()).isEqualTo(BaselineMethod.APPROXIMATED);
        assertThat(metrics.baselineShare()).isCloseTo(1.0 / 3, within(0.0001));
        assertThat(metrics.lift()).isCloseTo(0.8 - 1.0 / 3, within(0.0001));
    }

    @Test
    @DisplayName("with enough organic resolutions the baseline is measured, not guessed")
    void aMeasuredBaselineWinsOverTheApproximation() {
        PartnerPromotion tea = teaPromotion(PromotionType.PAID_PARTNER);
        resolution("чай", OWN_TEA_ID, tea.getId(), 4);
        // Six organic resolutions, two of which the ordinary matcher gave the promoted product anyway.
        resolution("чай", OWN_TEA_ID, null, 4);
        resolution("чай", OWN_TEA_ID, null, 4);
        resolution("чай", "p-tea-other", null, 4);
        resolution("чай", "p-tea-other", null, 4);
        resolution("чай", "p-tea-third", null, 4);
        resolution("чай", "p-tea-third", null, 4);

        PromotionMetrics metrics = metricsService.metrics().getFirst();

        assertThat(metrics.baselineMethod()).isEqualTo(BaselineMethod.MEASURED);
        assertThat(metrics.baselineShare()).isCloseTo(2.0 / 6, within(0.0001));
        assertThat(metrics.featuredShareRate()).isCloseTo(1.0 / 7, within(0.0001));
    }

    @Test
    @DisplayName("no candidate counts and too few organic rows means no baseline and no lift")
    void withoutDataTheBaselineIsUnknownAndLiftIsNotInvented() {
        PartnerPromotion tea = teaPromotion(PromotionType.PAID_PARTNER);
        resolution("чай", OWN_TEA_ID, tea.getId(), null);
        resolution("чай", "p-tea-other", null, null);

        PromotionMetrics metrics = metricsService.metrics().getFirst();

        assertThat(metrics.featuredShareRate()).isEqualTo(0.5);
        assertThat(metrics.baselineMethod()).isEqualTo(BaselineMethod.UNKNOWN);
        assertThat(metrics.baselineShare()).isNull();
        assertThat(metrics.lift()).isNull();
    }

    @Test
    @DisplayName("a placement nobody has resolved yet has no share at all")
    void aPlacementWithNoResolutionsHasNoShare() {
        teaPromotion(PromotionType.PAID_PARTNER);

        PromotionMetrics metrics = metricsService.metrics().getFirst();

        assertThat(metrics.categoryResolutions()).isZero();
        assertThat(metrics.featuredShareRate()).isNull();
        assertThat(metrics.lift()).isNull();
    }

    @Test
    @DisplayName("paid placements and own brands are rolled up separately, never blended")
    void theTwoValuePoolsAreReportedApart() {
        PartnerPromotion paid = promotionRepository.save(PartnerPromotion.builder()
                .id(UUID.randomUUID())
                .partnerName("Яготинське")
                .categoryOrQuery("молоко")
                .silpoProductId("p-milk-partner")
                .productName("Молоко Яготинське 2.5% 900г")
                .priorityWeight(100)
                .promotionType(PromotionType.PAID_PARTNER)
                .status(PartnerPromotionStatus.ACTIVE)
                .createdAt(Instant.now())
                .build());
        PartnerPromotion own = teaPromotion(PromotionType.OWN_BRAND_MARGIN_BOOST);
        resolution("молоко", "p-milk-partner", paid.getId(), 4);
        resolution("чай", OWN_TEA_ID, own.getId(), 3);
        resolution("чай", "p-tea-other", null, 3);

        String report = metricsService.report();

        int paidHeading = report.indexOf("## Платні розміщення (PAID_PARTNER)");
        int ownHeading = report.indexOf("## Власні марки (OWN_BRAND_MARGIN_BOOST)");
        assertThat(paidHeading).isNotNegative();
        assertThat(ownHeading).isGreaterThan(paidHeading);
        assertThat(report.indexOf("Яготинське")).isBetween(paidHeading, ownHeading);
        assertThat(report.indexOf("Сільпо власна марка")).isGreaterThan(ownHeading);
        // 1 of 2 tea resolutions, and the method is never left off a baseline.
        assertThat(report).contains("50 %").contains("наближення (1/N кандидатів)");
        // The funnel is not strictly nested — a rate above 100 % is possible and the report says why.
        assertThat(report).contains("може перевищити 100 %");
    }

    @Test
    @DisplayName("an unknown baseline prints a dash for the lift too")
    void anUnknownBaselineNeverBecomesALiftNumber() {
        PartnerPromotion tea = teaPromotion(PromotionType.PAID_PARTNER);
        resolution("чай", OWN_TEA_ID, tea.getId(), null);

        String report = metricsService.report();

        assertThat(report).contains("| — | — | — |");
    }

    @Test
    @DisplayName("the pool rollup counts only the categories that pool actually plays in")
    void theOverallShareIgnoresCategoriesNobodyHasAPlacementIn() {
        PartnerPromotion tea = teaPromotion(PromotionType.OWN_BRAND_MARGIN_BOOST);
        resolution("чай", OWN_TEA_ID, tea.getId(), 3);
        resolution("чай", "p-tea-other", null, 3);
        // Nobody bids on bread; counting it would drag the share towards zero and describe nothing.
        resolution("хліб", "p-bread", null, 3);

        PromotionRollup own = rollup(PromotionType.OWN_BRAND_MARGIN_BOOST);

        assertThat(own.categoryResolutions()).isEqualTo(2);
        assertThat(own.featuredResolutions()).isEqualTo(1);
        assertThat(own.featuredShareRate()).isEqualTo(0.5);
        assertThat(own.activeCategories()).isEqualTo(1);
    }

    @Test
    @DisplayName("two placements in one category do not count that category's lines twice")
    void oneCategoryIsOneDenominatorHoweverManyPlacementsSitOnIt() {
        PartnerPromotion first = milkPromotion("Яготинське", "p-milk-partner", PartnerPromotionStatus.ACTIVE);
        milkPromotion("Пирятин", "p-milk-other", PartnerPromotionStatus.PAUSED);
        resolution("молоко", "p-milk-partner", first.getId(), 4);
        resolution("молоко", "p-milk-generic", null, 4);

        PromotionRollup paid = rollup(PromotionType.PAID_PARTNER);

        assertThat(paid.categoryResolutions()).isEqualTo(2);
        assertThat(paid.featuredShareRate()).isEqualTo(0.5);
        // Only the live placement counts as coverage; a paused one holds no shelf.
        assertThat(paid.activeCategories()).isEqualTo(1);
    }

    @Test
    @DisplayName("the combined row is the two pools added up, and the pools stay separate")
    void theCombinedRollupSumsBothPools() {
        PartnerPromotion paid = milkPromotion("Яготинське", "p-milk-partner", PartnerPromotionStatus.ACTIVE);
        PartnerPromotion own = teaPromotion(PromotionType.OWN_BRAND_MARGIN_BOOST);
        resolution("молоко", "p-milk-partner", paid.getId(), 4);
        resolution("чай", OWN_TEA_ID, own.getId(), 3);
        confirmedOrder(paid.getId(), List.of(line("p-milk-partner", "1", "40.00")));
        confirmedOrder(own.getId(), List.of(line(OWN_TEA_ID, "1", "60.00")));

        assertThat(rollup(PromotionType.PAID_PARTNER).attributedRevenue()).isEqualByComparingTo("40.00");
        assertThat(rollup(PromotionType.OWN_BRAND_MARGIN_BOOST).attributedRevenue())
                .isEqualByComparingTo("60.00");
        PromotionRollup both = rollup(null);
        assertThat(both.attributedRevenue()).isEqualByComparingTo("100.00");
        assertThat(both.featuredShareRate()).isEqualTo(1.0);
        assertThat(both.activeCategories()).isEqualTo(2);
    }

    private PromotionRollup rollup(PromotionType type) {
        return metricsService.rollups().stream()
                .filter(row -> row.type() == type)
                .findFirst()
                .orElseThrow();
    }

    private PartnerPromotion milkPromotion(String partner, String productId, PartnerPromotionStatus status) {
        return promotionRepository.save(PartnerPromotion.builder()
                .id(UUID.randomUUID())
                .partnerName(partner)
                .categoryOrQuery("молоко")
                .silpoProductId(productId)
                .productName("Молоко " + partner + " 2.5% 900г")
                .priorityWeight(100)
                .promotionType(PromotionType.PAID_PARTNER)
                .status(status)
                .createdAt(Instant.now())
                .build());
    }

    @Test
    @DisplayName("the report shows the money, the pool totals and how many lines had no price")
    void theReportCarriesAttributedRevenue() {
        PartnerPromotion tea = teaPromotion(PromotionType.OWN_BRAND_MARGIN_BOOST);
        resolution("чай", OWN_TEA_ID, tea.getId(), 3);
        confirmedOrder(tea.getId(), List.of(line(OWN_TEA_ID, "2", "47.90")));
        confirmedOrder(tea.getId(), List.of(line(OWN_TEA_ID, "1", null)));

        String report = metricsService.report();

        assertThat(report).contains("Атрибутовано, ₴");
        assertThat(report).contains("95.80");
        assertThat(report).contains("атрибутовано 95.80 ₴");
        assertThat(report).contains("без збереженої ціни: 1");
    }

    @Test
    @DisplayName("with nothing configured the report says so instead of printing empty tables")
    void anEmptyReportIsHonest() {
        assertThat(metricsService.report()).contains("Жодного розміщення ще не налаштовано");
    }
}
