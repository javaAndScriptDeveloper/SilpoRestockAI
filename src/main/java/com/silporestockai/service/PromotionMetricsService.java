package com.silporestockai.service;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.entity.PartnerPromotionEvent;
import com.silporestockai.model.BaselineMethod;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.PartnerPromotionEventType;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.model.PromotionMetrics;
import com.silporestockai.model.PromotionRollup;
import com.silporestockai.model.PromotionType;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.utils.CategoryWords;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
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
    private final CustomerOrderRepository customerOrderRepository;
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
                .append("> FSR, а не завищує. Базлайн і lift завжди підписані методом розрахунку.\n")
                .append("> Події лічаться незалежно одна від одної: підтверджене замовлення записується для\n")
                .append("> кожного активного розміщення, товар якого був у замовленні, навіть якщо той кошик не\n")
                .append("> дав події «у кошику» (повторне замовлення, минуле замовлення). Тому «кошик→замовлення»\n")
                .append("> може перевищити 100 % — це не помилка рахунку, а наслідок того, що воронка не строго\n")
                .append("> вкладена.\n")
                .append("> «Атрибутовано» — сума рядків саме промотованого товару в підтверджених замовленнях\n")
                .append("> (ціна × кількість), а не вартість усього кошика і не оцінка. Це те саме, що retail media\n")
                .append("> називає Attributed Sales.\n");
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
                .append("FSR | Базлайн | Метод | Lift | Атрибутовано, ₴ |\n");
        text.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
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
                    .append(" | ")
                    .append(metrics.attributedRevenue().toPlainString())
                    .append(" |\n");
        }
        long added = rows.stream().mapToLong(PromotionMetrics::addedToCart).sum();
        long confirmed =
                rows.stream().mapToLong(PromotionMetrics::confirmedOrders).sum();
        Set<String> categories = rows.stream()
                .filter(metrics -> metrics.promotion().getStatus() == PartnerPromotionStatus.ACTIVE)
                .map(metrics -> metrics.promotion().getCategoryOrQuery())
                .collect(Collectors.toSet());
        BigDecimal revenue = rows.stream()
                .map(PromotionMetrics::attributedRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        long missingPrice =
                rows.stream().mapToLong(PromotionMetrics::ordersMissingPrice).sum();
        text.append("\nРазом: кошик→замовлення ")
                .append(percent(confirmed, added))
                .append(", активних категорій ")
                .append(categories.size())
                .append(", атрибутовано ")
                .append(revenue.toPlainString())
                .append(" ₴");
        if (missingPrice > 0) {
            // The sum is short by these lines, and saying so is cheaper than a number nobody can reconcile.
            text.append(" (рядків без збереженої ціни: ").append(missingPrice).append(")");
        }
        text.append(".\n\n");
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
        Map<UUID, List<CustomerOrder>> orders = confirmedOrdersByPromotion();
        return promotionRepository.findAll().stream()
                .sorted(Comparator.comparing(PartnerPromotion::getCreatedAt))
                .map(promotion -> metricsFor(promotion, logs, orders.getOrDefault(promotion.getId(), List.of())))
                .toList();
    }

    /**
     * Both value pools rolled up, plus the combined row — the three numbers a dashboard leads with (task 64).
     *
     * <p>The list is always three rows in the same order, even when a pool is empty, so a panel binding to it never
     * has a series appear and disappear underneath it.
     */
    public List<PromotionRollup> rollups() {
        List<PromotionMetrics> all = metrics();
        List<CategoryResolutionLog> logs = resolutionLogRepository.findAll();
        List<PromotionRollup> rollups = new ArrayList<>();
        rollups.add(rollup(PromotionType.PAID_PARTNER, byType(all, PromotionType.PAID_PARTNER), logs));
        rollups.add(
                rollup(PromotionType.OWN_BRAND_MARGIN_BOOST, byType(all, PromotionType.OWN_BRAND_MARGIN_BOOST), logs));
        rollups.add(rollup(null, all, logs));
        return rollups;
    }

    private static PromotionRollup rollup(
            PromotionType type, List<PromotionMetrics> rows, List<CategoryResolutionLog> logs) {
        Set<UUID> promotionIds =
                rows.stream().map(row -> row.promotion().getId()).collect(Collectors.toSet());
        Set<String> categories = rows.stream()
                .map(row -> row.promotion().getCategoryOrQuery())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Each resolution counts once towards the denominator, however many placements of this pool claim its
        // category — two milk placements must not make the milk lines look like twice as much shelf.
        long denominator = logs.stream()
                .filter(row ->
                        categories.stream().anyMatch(category -> CategoryWords.matches(row.getLineName(), category)))
                .count();
        long featured = logs.stream()
                .filter(row -> row.getPromotionId() != null && promotionIds.contains(row.getPromotionId()))
                .count();

        BigDecimal revenue = rows.stream()
                .map(PromotionMetrics::attributedRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        int activeCategories = (int) rows.stream()
                .filter(row -> row.promotion().getStatus() == PartnerPromotionStatus.ACTIVE)
                .map(row -> row.promotion().getCategoryOrQuery())
                .distinct()
                .count();
        return new PromotionRollup(
                type,
                denominator == 0 ? null : (double) featured / denominator,
                featured,
                denominator,
                revenue,
                activeCategories);
    }

    /**
     * The confirmed orders behind each placement's {@code CONFIRMED_ORDER} events, read in two queries rather than
     * two per placement. An order id appearing twice for the same placement — a repeat confirmation — is kept once:
     * the money moved once.
     */
    private Map<UUID, List<CustomerOrder>> confirmedOrdersByPromotion() {
        List<PartnerPromotionEvent> events = eventRepository.findByEventType(PartnerPromotionEventType.CONFIRMED_ORDER);
        Set<UUID> orderIds = events.stream()
                .map(PartnerPromotionEvent::getOrderId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CustomerOrder> orders = customerOrderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(CustomerOrder::getId, order -> order));

        Map<UUID, Map<UUID, CustomerOrder>> byPromotion = new LinkedHashMap<>();
        for (PartnerPromotionEvent event : events) {
            CustomerOrder order = event.getOrderId() == null ? null : orders.get(event.getOrderId());
            if (order != null) {
                byPromotion
                        .computeIfAbsent(event.getPromotionId(), id -> new LinkedHashMap<>())
                        .putIfAbsent(order.getId(), order);
            }
        }
        return byPromotion.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue().values())));
    }

    private PromotionMetrics metricsFor(
            PartnerPromotion promotion, List<CategoryResolutionLog> logs, List<CustomerOrder> orders) {
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

        BigDecimal revenue = BigDecimal.ZERO;
        long missingPrice = 0;
        for (CustomerOrder order : orders) {
            for (BasketItem line : order.getItems() == null ? List.<BasketItem>of() : order.getItems()) {
                if (line == null || !promotion.getSilpoProductId().equals(line.silpoProductId())) {
                    continue;
                }
                if (line.price() == null) {
                    // Counted, not guessed: a zero here would quietly shrink the money and still look exact.
                    missingPrice++;
                    continue;
                }
                revenue =
                        revenue.add(line.price().multiply(line.quantity() == null ? BigDecimal.ONE : line.quantity()));
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
                method,
                revenue.setScale(2, RoundingMode.HALF_UP),
                missingPrice);
    }
}
