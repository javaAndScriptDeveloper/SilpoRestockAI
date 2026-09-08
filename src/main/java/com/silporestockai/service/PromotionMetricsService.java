package com.silporestockai.service;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.model.BaselineMethod;
import com.silporestockai.model.PartnerPromotionEventType;
import com.silporestockai.model.PromotionMetrics;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.utils.CategoryWords;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Share of category, not raw counts (task 63).
 *
 * <p>«Featured four times» says nothing about whether four is a lot. «Answered four of the five tea lines our
 * households asked for» does, and it is the number both a paying partner and Silpo's own margin review actually
 * want. The denominator comes from {@code category_resolution_log}, matched with the same word rule the cart used
 * to decide the placement in the first place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionMetricsService {

    /** Below this many organic resolutions a measured baseline is noise wearing a decimal point. */
    static final int MEASURED_BASELINE_MIN_ROWS = 5;

    private final PartnerPromotionRepository promotionRepository;
    private final PartnerPromotionEventRepository eventRepository;
    private final CategoryResolutionLogRepository resolutionLogRepository;

    public List<PromotionMetrics> metrics() {
        List<CategoryResolutionLog> logs = resolutionLogRepository.findAll();
        return promotionRepository.findAll().stream()
                .sorted(Comparator.comparing(PartnerPromotion::getCreatedAt))
                .map(promotion -> metricsFor(promotion, logs))
                .toList();
    }

    private PromotionMetrics metricsFor(PartnerPromotion promotion, List<CategoryResolutionLog> logs) {
        List<CategoryResolutionLog> category = logs.stream()
                .filter(row -> CategoryWords.matches(row.getLineName(), promotion.getCategoryOrQuery()))
                .toList();
        long featured = category.stream()
                .filter(row -> promotion.getId().equals(row.getPromotionId()))
                .count();
        Double share = category.isEmpty() ? null : (double) featured / category.size();

        List<CategoryResolutionLog> organic =
                category.stream().filter(row -> row.getPromotionId() == null).toList();
        Double baseline = null;
        BaselineMethod method = BaselineMethod.UNKNOWN;
        if (organic.size() >= MEASURED_BASELINE_MIN_ROWS) {
            // Measured: how often the ordinary matcher reached for this very product with no help at all.
            long organicHits = organic.stream()
                    .filter(row -> promotion.getSilpoProductId().equals(row.getResolvedProductId()))
                    .count();
            baseline = (double) organicHits / organic.size();
            method = BaselineMethod.MEASURED;
        } else {
            // Approximated: one brand's naive share of the candidates the catalog offered. Rows without a
            // candidate count stay out of the average rather than being counted as zero.
            OptionalDouble candidates = category.stream()
                    .filter(row -> row.getCandidateCount() != null && row.getCandidateCount() > 0)
                    .mapToInt(CategoryResolutionLog::getCandidateCount)
                    .average();
            if (candidates.isPresent()) {
                baseline = 1.0 / candidates.getAsDouble();
                method = BaselineMethod.APPROXIMATED;
            }
        }

        return new PromotionMetrics(
                promotion,
                eventRepository.countByPromotionIdAndEventType(promotion.getId(), PartnerPromotionEventType.IMPRESSION),
                eventRepository.countByPromotionIdAndEventType(
                        promotion.getId(), PartnerPromotionEventType.ADDED_TO_CART),
                eventRepository.countByPromotionIdAndEventType(
                        promotion.getId(), PartnerPromotionEventType.CONFIRMED_ORDER),
                featured,
                category.size(),
                share,
                baseline,
                method);
    }
}
