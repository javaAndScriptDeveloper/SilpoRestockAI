package com.silporestockai.service;

import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.entity.PartnerPromotionEvent;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.DietType;
import com.silporestockai.model.OrderConfirmedEvent;
import com.silporestockai.model.PartnerPromotionEventType;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.repository.CustomerOrderRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.utils.CategoryWords;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Paid featured placement (task 46): which partner product, if any, should answer a list line — and the funnel
 * events a partner is sold.
 *
 * <p>Two rules this class exists to keep, in this order. A promotion never adds a line: it is consulted only for
 * lines the household already put on the list, so «молоко» can become a particular milk and nothing can become
 * chips. And a household's restrictions win before matching is even attempted: a lactose-free household never
 * sees a milk placement, whatever the partner paid. The restriction check is keyword-based (see
 * {@link #conflicts}) — good enough to never surface an obvious conflict, honest about not being an allergen
 * database.
 *
 * <p>Liveness is not this class's job: {@code CartBuildingService} re-verifies the promoted product against the
 * catalog in the same search it was going to run anyway, and only then calls {@link #recordImpression}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerPromotionService {

    /** Restriction chip codes from the onboarding form → what a promoted product's name must not contain. */
    private static final Map<String, List<String>> RESTRICTION_KEYWORDS = Map.of(
            "lactose", List.of("молок", "молоч", "сир", "йогурт", "кефір", "вершк", "масло вершк", "ряжанк", "сметан"),
            "gluten", List.of("хліб", "борошн", "макарон", "спагет", "пшениц", "булк", "печив", "батон", "лаваш"),
            "nuts", List.of("горіх", "арахіс", "мигдал", "фундук", "фісташ", "кеш'ю", "кешью"),
            "seafood", List.of("риб", "креветк", "мідії", "кальмар", "лосос", "тунець", "морепродукт", "оселедец"));

    private static final List<String> VEGETARIAN_KEYWORDS = List.of(
            "м'яс", "мяс", "курк", "куряч", "свинин", "яловичин", "ковбас", "сосиск", "шинк", "риб", "бекон", "панчет");
    private static final List<String> VEGAN_KEYWORDS = List.of(
            "м'яс",
            "мяс",
            "курк",
            "куряч",
            "свинин",
            "яловичин",
            "ковбас",
            "сосиск",
            "шинк",
            "риб",
            "бекон",
            "панчет",
            "яйц",
            "яєч",
            "молок",
            "молоч",
            "сир",
            "йогурт",
            "кефір",
            "вершк",
            "сметан",
            "мед");

    private final PartnerPromotionRepository partnerPromotionRepository;
    private final PartnerPromotionEventRepository eventRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final Clock clock;

    /** Every promotion that could be surfaced right now, highest priority first. */
    public List<PartnerPromotion> activePromotions() {
        Instant now = clock.instant();
        return partnerPromotionRepository.findByStatus(PartnerPromotionStatus.ACTIVE).stream()
                .filter(promotion -> promotion.getActiveFrom() == null
                        || !promotion.getActiveFrom().isAfter(now))
                .filter(promotion -> promotion.getActiveTo() == null
                        || promotion.getActiveTo().isAfter(now))
                .sorted(Comparator.comparingInt(PartnerPromotion::getPriorityWeight)
                        .reversed())
                .toList();
    }

    /**
     * The promotion that may answer this list line, if any: its category word is in the line, and nothing in the
     * household's restrictions, dislikes or diet rules the product out. Restrictions are checked first, so a
     * conflicting promotion is never even considered — not surfaced and then filtered.
     */
    public Optional<PartnerPromotion> match(List<PartnerPromotion> candidates, String lineName, UserProfile profile) {
        if (CategoryWords.normalise(lineName).isBlank()) {
            return Optional.empty();
        }
        for (PartnerPromotion promotion : candidates) {
            if (!CategoryWords.matches(lineName, promotion.getCategoryOrQuery())) {
                continue;
            }
            Optional<String> conflict = conflicts(promotion, profile);
            if (conflict.isPresent()) {
                log.info(
                        "partner promotion {} skipped for «{}»: conflicts with the household's «{}»",
                        promotion.getId(),
                        lineName,
                        conflict.get());
                continue;
            }
            return Optional.of(promotion);
        }
        return Optional.empty();
    }

    /** The first household rule the promoted product or its category trips, if any. */
    static Optional<String> conflicts(PartnerPromotion promotion, UserProfile profile) {
        if (profile == null) {
            return Optional.empty();
        }
        String haystack = CategoryWords.normalise(promotion.getProductName()) + " "
                + CategoryWords.normalise(promotion.getCategoryOrQuery());
        List<String> rules = new ArrayList<>();
        for (String restriction :
                profile.getDietaryRestrictions() == null ? List.<String>of() : profile.getDietaryRestrictions()) {
            String code = CategoryWords.normalise(restriction);
            rules.addAll(RESTRICTION_KEYWORDS.getOrDefault(code, List.of(code)));
        }
        for (String disliked : profile.getDislikedFoods() == null ? List.<String>of() : profile.getDislikedFoods()) {
            rules.add(CategoryWords.normalise(disliked));
        }
        if (profile.getDietType() == DietType.VEGAN) {
            rules.addAll(VEGAN_KEYWORDS);
        } else if (profile.getDietType() == DietType.VEGETARIAN) {
            rules.addAll(VEGETARIAN_KEYWORDS);
        }
        return rules.stream()
                .filter(rule -> !rule.isBlank() && haystack.contains(rule))
                .findFirst();
    }

    public void recordImpression(PartnerPromotion promotion, UUID userId) {
        record(promotion.getId(), userId, null, PartnerPromotionEventType.IMPRESSION);
    }

    public void recordAddedToCart(UUID promotionId, UUID userId) {
        record(promotionId, userId, null, PartnerPromotionEventType.ADDED_TO_CART);
    }

    /** A promoted product that survived to a confirmed order is the number a partner actually pays for. */
    @EventListener
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        try {
            List<PartnerPromotion> active = activePromotions();
            if (active.isEmpty()) {
                return;
            }
            Optional<CustomerOrder> order = customerOrderRepository.findById(event.orderId());
            if (order.isEmpty() || order.get().getItems() == null) {
                return;
            }
            Set<String> productIds = order.get().getItems().stream()
                    .map(BasketItem::silpoProductId)
                    .filter(id -> id != null)
                    .collect(java.util.stream.Collectors.toSet());
            for (PartnerPromotion promotion : active) {
                if (productIds.contains(promotion.getSilpoProductId())) {
                    record(
                            promotion.getId(),
                            event.userId(),
                            event.orderId(),
                            PartnerPromotionEventType.CONFIRMED_ORDER);
                }
            }
        } catch (RuntimeException e) {
            // Evidence for a report must never break the order that just went through.
            log.warn(
                    "could not record confirmed-order promotion events for order {}: {}",
                    event.orderId(),
                    e.getMessage());
        }
    }

    private void record(UUID promotionId, UUID userId, UUID orderId, PartnerPromotionEventType type) {
        try {
            eventRepository.save(PartnerPromotionEvent.builder()
                    .id(UUID.randomUUID())
                    .promotionId(promotionId)
                    .userId(userId)
                    .orderId(orderId)
                    .eventType(type)
                    .occurredAt(clock.instant())
                    .build());
        } catch (RuntimeException e) {
            log.warn("could not record {} for promotion {}: {}", type, promotionId, e.getMessage());
        }
    }
}
