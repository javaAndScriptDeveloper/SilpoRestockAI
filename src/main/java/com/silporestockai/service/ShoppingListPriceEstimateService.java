package com.silporestockai.service;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.PriceEstimate;
import com.silporestockai.repository.BaselineBasketRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
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
 * curated with ({@code shopping_list_item.estimated_price}). Any other line is looked up in the household's current
 * baseline basket — the last cart they confirmed, prices included — see {@link #baselineMatch} for the three ways a
 * household's word is read against Silpo's catalog name. A line neither source knows stays unpriced and is counted
 * as such; the number shown is never padded with a guess.
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
        List<BasketItem> priceable = new ArrayList<>();
        Map<String, BasketItem> byRequestedName = new HashMap<>();
        Map<String, BasketItem> byCatalogName = new HashMap<>();
        for (BasketItem line : baseline == null ? List.<BasketItem>of() : baseline) {
            if (line == null || line.price() == null) {
                continue;
            }
            priceable.add(line);
            if (line.requestedName() != null) {
                byRequestedName.putIfAbsent(normalise(line.requestedName()), line);
            }
            if (line.name() != null) {
                byCatalogName.putIfAbsent(normalise(line.name()), line);
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        int priced = 0;
        int unpriced = 0;
        for (ShoppingListItem item : items) {
            BigDecimal line = linePrice(item, baselineMatch(item, byRequestedName, byCatalogName, priceable));
            if (line == null) {
                unpriced++;
            } else {
                priced++;
                total = total.add(line);
            }
        }
        return new PriceEstimate(total.setScale(2, RoundingMode.HALF_UP), priced, unpriced);
    }

    /**
     * The baseline line this list line last came as, if any of three readings finds it.
     *
     * <p>First the request the household actually made last time ({@code BasketItem.requestedName}) — an exact
     * pairing, recorded when the cart was built, and the only one that needs no judgement. Then the catalog name
     * itself, which is what a ready-meals line matches on. Only then the reading that has to guess: a catalog name
     * that contains every word of what the list asks for. «Молоко» finds «Молоко «Яготинське» 2,6% п/е», and
     * «Куряче філе» does not find «Філе курчати-бройлера» — one word short is no match, because an estimate built
     * out of near-misses is worse than one that admits it priced 5 lines of 12.
     */
    private static BasketItem baselineMatch(
            ShoppingListItem item,
            Map<String, BasketItem> byRequestedName,
            Map<String, BasketItem> byCatalogName,
            List<BasketItem> priceable) {
        String name = normalise(item.getName());
        BasketItem exact = byRequestedName.get(name);
        if (exact == null) {
            exact = byCatalogName.get(name);
        }
        if (exact != null) {
            return exact;
        }
        List<String> asked = words(item.getName());
        if (asked.isEmpty()) {
            return null;
        }
        // Fewest words wins: «Молоко» is the plain milk before it is the condensed milk, whatever order the
        // baseline happens to be in.
        BasketItem best = null;
        int bestWords = Integer.MAX_VALUE;
        for (BasketItem line : priceable) {
            List<String> catalog = words(line.name());
            if (catalog.size() < bestWords && catalog.containsAll(asked)) {
                best = line;
                bestWords = catalog.size();
            }
        }
        return best;
    }

    /**
     * Words of three letters or more, case-folded. Splitting on everything that is not a letter is what keeps
     * «Київхліб» from answering for «хліб»: the catalog name has to carry the word itself, not merely the letters.
     */
    private static List<String> words(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{L}]+"))
                .filter(word -> word.length() > 2)
                .toList();
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
