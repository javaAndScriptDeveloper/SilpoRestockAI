package com.silporestockai.service;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.PriceEstimate;
import com.silporestockai.repository.BaselineBasketRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Prices a shopping list from what the system already knows, without asking Silpo (task 39).
 *
 * <p>Two sources, in order. A line the {@code READY_MEALS_ONLY} fork produced carries the catalog unit price it was
 * curated with ({@code shopping_list_item.estimated_price}). Any other line is looked up by name in the household's
 * current baseline basket — the last cart they confirmed, prices included. A line neither source knows stays
 * unpriced and is counted as such; the number shown is never padded with a guess.
 *
 * <p>The cart itself, built later by {@code CartBuildingService}, is the real price. This exists so somebody can say
 * «дорого, прибери X» before a cart is built, not after.
 */
@Service
@RequiredArgsConstructor
public class ShoppingListPriceEstimateService {

    private final BaselineBasketRepository baselineBasketRepository;

    public PriceEstimate estimate(UUID userId, List<ShoppingListItem> items) {
        List<BasketItem> baseline = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(userId)
                .map(BaselineBasket::getItems)
                .orElse(List.of());
        return estimate(items, baseline);
    }

    /** The pure rule, kept static so a test needs no repository. */
    public static PriceEstimate estimate(List<ShoppingListItem> items, List<BasketItem> baseline) {
        Map<String, BasketItem> baselineByName = new HashMap<>();
        for (BasketItem line : baseline == null ? List.<BasketItem>of() : baseline) {
            if (line != null && line.name() != null && line.price() != null) {
                baselineByName.putIfAbsent(normalise(line.name()), line);
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        int priced = 0;
        int unpriced = 0;
        for (ShoppingListItem item : items) {
            BigDecimal line = linePrice(item, baselineByName.get(normalise(item.getName())));
            if (line == null) {
                unpriced++;
            } else {
                priced++;
                total = total.add(line);
            }
        }
        return new PriceEstimate(total.setScale(2, RoundingMode.HALF_UP), priced, unpriced);
    }

    private static BigDecimal linePrice(ShoppingListItem item, BasketItem baseline) {
        if (item.getEstimatedPrice() != null) {
            BigDecimal quantity = item.getQuantity() == null ? BigDecimal.ONE : item.getQuantity();
            return item.getEstimatedPrice().multiply(quantity);
        }
        if (baseline == null) {
            return null;
        }
        // A baseline line holds a line price for the quantity that was ordered. Scale it only when the two are
        // genuinely comparable; «2 шт» against «200 г» is a different question, and the old line price as-is is
        // closer to right than a made-up conversion.
        boolean comparable = item.getQuantity() != null
                && baseline.quantity() != null
                && baseline.quantity().signum() > 0
                && normalise(item.getUnit()).equals(normalise(baseline.unit()));
        if (!comparable) {
            return baseline.price();
        }
        return baseline.price().multiply(item.getQuantity()).divide(baseline.quantity(), 2, RoundingMode.HALF_UP);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
