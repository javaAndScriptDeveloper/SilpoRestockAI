package com.silporestockai.service;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.ShoppingListDelta;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Pure comparison logic, no DB or Claude dependency (task 21) — same shape as task 13's checkin-trend
 * diffing, reused rather than reinvented: match by name, compare quantity.
 *
 * <p>Names are matched exactly first, then by their words' stems in any order, then by one name's stems being
 * contained in the other's. The model renames lines every time it regenerates a plan — live, «Масло вершкове»
 * came back as «Вершкове масло», «Індиче філе» as «Філе індички», «Какао» as «Какао порошок», «Філе хека» as
 * «Риба» — and a diff that showed each of those as one line removed and one added read «+9, −7» for a change of
 * five. A rename is the same line, and a quantity that moved with it is a quantity change, not two events.
 */
@Service
public class ShoppingListDiffService {

    public ShoppingListDelta diff(List<ShoppingListItem> previous, List<ShoppingListItem> current) {
        Map<String, ShoppingListItem> previousByName = byName(previous);
        Map<String, ShoppingListItem> currentByName = byName(current);

        List<ShoppingListDelta.Line> added = new ArrayList<>();
        List<ShoppingListDelta.QuantityChange> quantityChanged = new ArrayList<>();
        int unchangedCount = 0;

        List<ShoppingListItem> unmatchedCurrent = new ArrayList<>();
        List<ShoppingListItem> unmatchedPrevious = new ArrayList<>();
        for (Map.Entry<String, ShoppingListItem> entry : currentByName.entrySet()) {
            ShoppingListItem before = previousByName.get(entry.getKey());
            if (before == null) {
                unmatchedCurrent.add(entry.getValue());
            } else if (quantityDiffers(before, entry.getValue())) {
                quantityChanged.add(change(before, entry.getValue()));
            } else {
                unchangedCount++;
            }
        }
        for (Map.Entry<String, ShoppingListItem> entry : previousByName.entrySet()) {
            if (!currentByName.containsKey(entry.getKey())) {
                unmatchedPrevious.add(entry.getValue());
            }
        }

        // Second pass over what exact names could not pair: the same words in another order, then a name that
        // is a shorter form of the other. Each previous line pairs with at most one current line.
        for (ShoppingListItem after : unmatchedCurrent) {
            ShoppingListItem before = takeRename(unmatchedPrevious, after);
            if (before == null) {
                added.add(lineOf(after));
            } else if (quantityDiffers(before, after)) {
                quantityChanged.add(change(before, after));
            } else {
                unchangedCount++;
            }
        }

        List<ShoppingListDelta.Line> removed = new ArrayList<>();
        for (ShoppingListItem gone : unmatchedPrevious) {
            removed.add(lineOf(gone));
        }

        return new ShoppingListDelta(added, removed, quantityChanged, unchangedCount);
    }

    private static ShoppingListItem takeRename(List<ShoppingListItem> candidates, ShoppingListItem after) {
        Set<String> stems = stems(after.getName());
        if (stems.isEmpty()) {
            return null;
        }
        ShoppingListItem sameWords = null;
        ShoppingListItem shorterForm = null;
        for (ShoppingListItem candidate : candidates) {
            Set<String> other = stems(candidate.getName());
            if (other.equals(stems)) {
                sameWords = candidate;
                break;
            }
            if (shorterForm == null && (other.containsAll(stems) || stems.containsAll(other))) {
                shorterForm = candidate;
            }
        }
        ShoppingListItem match = sameWords != null ? sameWords : shorterForm;
        if (match != null) {
            candidates.remove(match);
        }
        return match;
    }

    /** The first four letters of every word, so «індиче»/«індички» and «риба»/«риби» meet; short words lose one. */
    static Set<String> stems(String name) {
        Set<String> stems = new TreeSet<>();
        for (String token : normalise(name).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() < 2) {
                continue;
            }
            int keep = token.length() <= 4 ? Math.max(2, token.length() - 1) : 4;
            stems.add(token.substring(0, keep));
        }
        return stems;
    }

    private static ShoppingListDelta.QuantityChange change(ShoppingListItem before, ShoppingListItem after) {
        return new ShoppingListDelta.QuantityChange(
                after.getName(), before.getQuantity(), after.getQuantity(), after.getUnit());
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

    /**
     * Compared in base units: the model writes «1 кг» one week and «1000 г» the next, and a delta that reads
     * «Рис: 1 → 1000 г» is a unit change, not a quantity change. Kilograms and litres become grams and millilitres;
     * anything else is compared as written.
     */
    private static boolean quantityDiffers(ShoppingListItem before, ShoppingListItem after) {
        BigDecimal a = inBaseUnits(before.getQuantity(), before.getUnit());
        BigDecimal b = inBaseUnits(after.getQuantity(), after.getUnit());
        if (a == null || b == null) {
            return a != b;
        }
        return a.compareTo(b) != 0;
    }

    private static BigDecimal inBaseUnits(BigDecimal quantity, String unit) {
        if (quantity == null) {
            return null;
        }
        String u = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        return switch (u) {
            case "кг", "kg", "л", "l" -> quantity.multiply(BigDecimal.valueOf(1000));
            default -> quantity;
        };
    }

    private static ShoppingListDelta.Line lineOf(ShoppingListItem item) {
        return new ShoppingListDelta.Line(item.getName(), item.getQuantity(), item.getUnit());
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
