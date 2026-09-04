package com.silporestockai.service;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.ShoppingListDelta;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Pure comparison logic, no DB or Claude dependency (task 21) — same shape as task 13's checkin-trend
 * diffing, reused rather than reinvented: match by name, compare quantity.
 */
@Service
public class ShoppingListDiffService {

    public ShoppingListDelta diff(List<ShoppingListItem> previous, List<ShoppingListItem> current) {
        Map<String, ShoppingListItem> previousByName = byName(previous);
        Map<String, ShoppingListItem> currentByName = byName(current);

        List<ShoppingListDelta.Line> added = new ArrayList<>();
        List<ShoppingListDelta.QuantityChange> quantityChanged = new ArrayList<>();
        int unchangedCount = 0;

        for (Map.Entry<String, ShoppingListItem> entry : currentByName.entrySet()) {
            ShoppingListItem before = previousByName.get(entry.getKey());
            ShoppingListItem after = entry.getValue();
            if (before == null) {
                added.add(lineOf(after));
            } else if (quantityDiffers(before.getQuantity(), after.getQuantity())) {
                quantityChanged.add(new ShoppingListDelta.QuantityChange(
                        after.getName(), before.getQuantity(), after.getQuantity(), after.getUnit()));
            } else {
                unchangedCount++;
            }
        }

        List<ShoppingListDelta.Line> removed = new ArrayList<>();
        for (Map.Entry<String, ShoppingListItem> entry : previousByName.entrySet()) {
            if (!currentByName.containsKey(entry.getKey())) {
                removed.add(lineOf(entry.getValue()));
            }
        }

        return new ShoppingListDelta(added, removed, quantityChanged, unchangedCount);
    }

    private static Map<String, ShoppingListItem> byName(List<ShoppingListItem> items) {
        Map<String, ShoppingListItem> byName = new LinkedHashMap<>();
        for (ShoppingListItem item : items) {
            // Two lines that normalise to the same name is not expected from an aggregated list — the last one
            // wins, same "don't fail the diff over it" spirit as the rest of this pipeline's lenient parsing.
            byName.put(normalise(item.getName()), item);
        }
        return byName;
    }

    private static boolean quantityDiffers(BigDecimal before, BigDecimal after) {
        if (before == null || after == null) {
            return before != after;
        }
        return before.compareTo(after) != 0;
    }

    private static ShoppingListDelta.Line lineOf(ShoppingListItem item) {
        return new ShoppingListDelta.Line(item.getName(), item.getQuantity(), item.getUnit());
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
