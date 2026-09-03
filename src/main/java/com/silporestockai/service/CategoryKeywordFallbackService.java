package com.silporestockai.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Guesses a category from an item's name when Claude's generation left {@code category} blank.
 *
 * <p>Deterministic keyword matching, not a model call: this exists precisely so a category the model omitted doesn't
 * require an extra AI round-trip to fill in — see {@code ShoppingListService}'s "AI called only on real change" rule.
 */
@Service
public class CategoryKeywordFallbackService {

    private static final String FALLBACK_CATEGORY = "Інше";

    /** Ordered so a more specific keyword can be checked before a broader one matches first. */
    private static final Map<String, String> KEYWORD_TO_CATEGORY = new LinkedHashMap<>();

    static {
        KEYWORD_TO_CATEGORY.put("молок", "Молочні продукти");
        KEYWORD_TO_CATEGORY.put("сир", "Молочні продукти");
        KEYWORD_TO_CATEGORY.put("йогурт", "Молочні продукти");
        KEYWORD_TO_CATEGORY.put("м'ясо", "М'ясо і птиця");
        KEYWORD_TO_CATEGORY.put("курк", "М'ясо і птиця");
        KEYWORD_TO_CATEGORY.put("філе", "М'ясо і птиця");
        KEYWORD_TO_CATEGORY.put("фарш", "М'ясо і птиця");
        KEYWORD_TO_CATEGORY.put("риб", "Риба і морепродукти");
        KEYWORD_TO_CATEGORY.put("овоч", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("цибул", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("картопл", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("помідор", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("фрукт", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("яблук", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("банан", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("гречк", "Крупи і бакалія");
        KEYWORD_TO_CATEGORY.put("рис", "Крупи і бакалія");
        KEYWORD_TO_CATEGORY.put("макарон", "Крупи і бакалія");
        KEYWORD_TO_CATEGORY.put("борошн", "Крупи і бакалія");
        KEYWORD_TO_CATEGORY.put("хліб", "Хлібобулочні вироби");
        KEYWORD_TO_CATEGORY.put("яйц", "Яйця");
    }

    public String categorize(String itemName) {
        if (itemName == null) {
            return FALLBACK_CATEGORY;
        }
        String lower = itemName.toLowerCase(Locale.ROOT);
        return KEYWORD_TO_CATEGORY.entrySet().stream()
                .filter(entry -> lower.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(FALLBACK_CATEGORY);
    }
}
