package com.silporestockai.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One line of a basket, as stored in the {@code items_json} column of {@code baseline_basket} and
 * {@code customer_order}.
 *
 * @param silpoProductId product id from the Silpo MCP catalogue; null for an item that could not be resolved
 * @param name human-readable name, which is what a check-in message will refer to
 * @param unit unit the quantity is counted in, e.g. {@code шт} or {@code кг}
 * @param quantity how much was ordered
 * @param price line price at the time of confirmation; prices move, so the snapshot keeps its own
 * @param requestedName the shopping list line this product was bought for, in the household's own words — «молоко»
 *     against a {@code name} of «Молоко «Яготинське» 2,6% п/е». Recorded so the baseline can price next week's list
 *     without asking Silpo (task 39); null for a line nobody asked for by name — a top-up, a partner placement, a
 *     product added in the Silpo app — and for every basket stored before this field existed.
 */
public record BasketItem(
        String silpoProductId, String name, String unit, BigDecimal quantity, BigDecimal price, String requestedName) {

    /** The pre-task-39 shape, and the one for a line no list line asked for. */
    public BasketItem(String silpoProductId, String name, String unit, BigDecimal quantity, BigDecimal price) {
        this(silpoProductId, name, unit, quantity, price, null);
    }

    /**
     * The pairings these lines already carry, for handing on to a cart read back fresh from Silpo — which knows
     * only Silpo's own names. Lines without both halves are left out; the first pairing for a product wins, since
     * two list lines can resolve to the same product.
     */
    public static Map<String, String> requestedNamesByProductId(List<BasketItem> lines) {
        Map<String, String> names = new LinkedHashMap<>();
        for (BasketItem line : lines == null ? List.<BasketItem>of() : lines) {
            if (line != null && line.silpoProductId() != null && line.requestedName() != null) {
                names.putIfAbsent(line.silpoProductId(), line.requestedName());
            }
        }
        return names;
    }
}
