package com.silporestockai.service;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.model.ResolvedProduct;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.utils.CategoryWords;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Records what every resolved shopping-list line actually became (task 63).
 *
 * <p>The share a partner or an own-brand review is shown needs a denominator, and only the lines nobody promoted
 * can provide one. Written once per cart build, after the second pass, from the final resolution list — so every
 * flow that goes through {@link CartBuildingService} is counted with no flow-specific code.
 *
 * <p>A failure here costs the report its rows and nothing else: the cart it describes has already been built, and
 * evidence must never break the thing it is evidence of. Same rule as {@code PartnerPromotionService.record}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryResolutionLogService {

    private final CategoryResolutionLogRepository repository;
    private final Clock clock;

    /**
     * @param candidateCounts how many plausible candidates the catalog offered, keyed by the lower-cased line name;
     *     a missing key means the count is unknown and is stored as null rather than invented
     */
    public void record(UUID userId, List<ResolvedProduct> resolved, Map<String, Integer> candidateCounts) {
        if (resolved == null || resolved.isEmpty()) {
            return;
        }
        try {
            Instant now = clock.instant();
            List<CategoryResolutionLog> rows = resolved.stream()
                    .map(product -> CategoryResolutionLog.builder()
                            .id(UUID.randomUUID())
                            .userId(userId)
                            .lineName(product.requestedName())
                            .resolvedProductId(product.productId())
                            .resolvedProductName(product.catalogName())
                            .promotionId(product.promotionId())
                            .candidateCount(
                                    candidateCounts == null
                                            ? null
                                            : candidateCounts.get(CategoryWords.normalise(product.requestedName())))
                            .occurredAt(now)
                            .build())
                    .toList();
            repository.saveAll(rows);
            log.debug("logged {} category resolutions for {}", rows.size(), userId);
        } catch (RuntimeException e) {
            log.warn("could not log {} category resolutions for {}: {}", resolved.size(), userId, e.getMessage());
        }
    }
}
