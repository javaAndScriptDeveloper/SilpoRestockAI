package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.model.BaselineMethod;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.model.PromotionMetrics;
import com.silporestockai.model.PromotionType;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.service.PromotionMetricsService;
import java.time.Instant;
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

    @BeforeEach
    void clean() {
        logRepository.deleteAll();
        eventRepository.deleteAll();
        promotionRepository.deleteAll();
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
}
