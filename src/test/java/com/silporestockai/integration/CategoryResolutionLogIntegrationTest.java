package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("the category resolution log (task 63)")
class CategoryResolutionLogIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CategoryResolutionLogRepository logRepository;

    @Autowired
    private PartnerPromotionRepository promotionRepository;

    @BeforeEach
    void clean() {
        logRepository.deleteAll();
        promotionRepository.deleteAll();
    }

    @Test
    @DisplayName("an ordinary resolution is stored with no promotion behind it")
    void anOrdinaryResolutionRoundTrips() {
        CategoryResolutionLog saved = logRepository.save(CategoryResolutionLog.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .lineName("Молоко")
                .resolvedProductId("p-milk-generic")
                .resolvedProductName("Молоко Селянське 900г")
                .promotionId(null)
                .candidateCount(4)
                .occurredAt(Instant.now())
                .build());

        CategoryResolutionLog read = logRepository.findById(saved.getId()).orElseThrow();
        assertThat(read.getPromotionId()).isNull();
        assertThat(read.getCandidateCount()).isEqualTo(4);
        assertThat(read.getLineName()).isEqualTo("Молоко");
    }

    @Test
    @DisplayName("deleting a placement keeps its resolutions and only forgets who won them")
    void deletingAPlacementLeavesTheDenominatorIntact() {
        PartnerPromotion promotion = promotionRepository.save(PartnerPromotion.builder()
                .id(UUID.randomUUID())
                .partnerName("Яготинське")
                .categoryOrQuery("молоко")
                .silpoProductId("p-milk-partner")
                .productName("Молоко Яготинське 2.5% 900г")
                .priorityWeight(100)
                .status(PartnerPromotionStatus.ACTIVE)
                .createdAt(Instant.now())
                .build());
        UUID logId = logRepository
                .save(CategoryResolutionLog.builder()
                        .id(UUID.randomUUID())
                        .userId(UUID.randomUUID())
                        .lineName("Молоко")
                        .resolvedProductId("p-milk-partner")
                        .resolvedProductName("Молоко Яготинське 2.5% 900г")
                        .promotionId(promotion.getId())
                        .candidateCount(4)
                        .occurredAt(Instant.now())
                        .build())
                .getId();

        promotionRepository.deleteById(promotion.getId());

        CategoryResolutionLog read = logRepository.findById(logId).orElseThrow();
        assertThat(read.getPromotionId()).isNull();
        assertThat(read.getResolvedProductId()).isEqualTo("p-milk-partner");
    }
}
