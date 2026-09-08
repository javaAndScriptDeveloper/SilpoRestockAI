package com.silporestockai.service;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.model.BaselineMethod;
import com.silporestockai.model.PartnerPromotionEventType;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.model.PromotionMetrics;
import com.silporestockai.model.PromotionType;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.utils.CategoryWords;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final Clock clock;

    /**
     * The operator-facing report behind {@code make promotions}: one section per value pool, never a blended total.
     * External revenue and internal margin are two different businesses and one combined figure would describe
     * neither of them.
     */
    public String report() {
        List<PromotionMetrics> all = metrics();
        StringBuilder text = new StringBuilder("# Партнерські розміщення — звіт (")
                .append(clock.instant())
                .append(")\n\n");
        if (all.isEmpty()) {
            return text.append("Жодного розміщення ще не налаштовано.\n").toString();
        }
        section(text, "Платні розміщення (PAID_PARTNER)", "Партнер", byType(all, PromotionType.PAID_PARTNER));
        section(
                text,
                "Власні марки (OWN_BRAND_MARGIN_BOOST)",
                "Бренд",
                byType(all, PromotionType.OWN_BRAND_MARGIN_BOOST));
        text.append("> FSR рахується від усіх розв'язань цієї категорії — включно з рядками, де розміщення не мало\n")
                .append("> права виграти (обмеження господарства). Це справжня частка категорії: вона занижує\n")
                .append("> FSR, а не завищує. Базлайн і lift завжди підписані методом розрахунку.\n");
        return text.toString();
    }

    private static List<PromotionMetrics> byType(List<PromotionMetrics> all, PromotionType type) {
        return all.stream()
                .filter(metrics -> metrics.promotion().getPromotionType() == type)
                .toList();
    }

    private static void section(StringBuilder text, String title, String owner, List<PromotionMetrics> rows) {
        text.append("## ").append(title).append("\n\n");
        if (rows.isEmpty()) {
            text.append("Порожньо.\n\n");
            return;
        }
        text.append("| ")
                .append(owner)
                .append(" | Категорія | Товар | Статус | Показів | У кошику | Підтверджено | Кошик→замовлення | ")
                .append("FSR | Базлайн | Метод | Lift |\n");
        text.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (PromotionMetrics metrics : rows) {
            PartnerPromotion promotion = metrics.promotion();
            text.append("| ")
                    .append(promotion.getPartnerName())
                    .append(" | ")
                    .append(promotion.getCategoryOrQuery())
                    .append(" | ")
                    .append(promotion.getProductName())
                    .append(" | ")
                    .append(promotion.getStatus())
                    .append(" | ")
                    .append(metrics.impressions())
                    .append(" | ")
                    .append(metrics.addedToCart())
                    .append(" | ")
                    .append(metrics.confirmedOrders())
                    .append(" | ")
                    .append(percent(metrics.confirmedOrders(), metrics.addedToCart()))
                    .append(" | ")
                    .append(share(metrics.featuredShareRate()))
                    .append(" | ")
                    .append(share(metrics.baselineShare()))
                    .append(" | ")
                    .append(metrics.baselineMethod().label())
                    .append(" | ")
                    .append(points(metrics.lift()))
                    .append(" |\n");
        }
        long added = rows.stream().mapToLong(PromotionMetrics::addedToCart).sum();
        long confirmed =
                rows.stream().mapToLong(PromotionMetrics::confirmedOrders).sum();
        Set<String> categories = rows.stream()
                .filter(metrics -> metrics.promotion().getStatus() == PartnerPromotionStatus.ACTIVE)
                .map(metrics -> metrics.promotion().getCategoryOrQuery())
                .collect(Collectors.toSet());
        text.append("\nРазом: кошик→замовлення ")
                .append(percent(confirmed, added))
                .append(", активних категорій ")
                .append(categories.size())
                .append(".\n\n");
    }

    private static String percent(long numerator, long denominator) {
        return denominator == 0 ? "—" : String.format(Locale.ROOT, "%.0f %%", 100.0 * numerator / denominator);
    }

    /** A fraction as a percentage, or «—» when we do not have one. Never a zero standing in for «unknown». */
    private static String share(Double fraction) {
        return fraction == null ? "—" : String.format(Locale.ROOT, "%.0f %%", 100.0 * fraction);
    }

    private static String points(Double fraction) {
        return fraction == null ? "—" : String.format(Locale.ROOT, "%+.0f п.п.", 100.0 * fraction);
    }

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
