package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.exception.ApplicationException;
import com.silporestockai.mapper.ShoppingListItemMapper;
import com.silporestockai.model.PlannedDay;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.PlannedMeal;
import com.silporestockai.model.WeeklyMealPlan;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collapses a week of meals into the list somebody actually shops from.
 *
 * <p>No model call: this is arithmetic. A weekly plan names the same onion in four meals, and what a shopper needs is
 * one line saying how much onion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListService {

    /** Own mapper, as elsewhere in the app: Boot 4 carries both Jackson 2 and Jackson 3. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MealPlanRepository mealPlanRepository;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final ShoppingListItemMapper shoppingListItemMapper;

    /**
     * Derives the list for a weekly plan, replacing whatever the plan had before.
     *
     * <p>Replacing rather than diffing: regeneration produces a new plan, and half of an old list next to half of a
     * new one is worse than either.
     */
    @Transactional
    public List<ShoppingListItem> deriveFromMealPlan(UUID mealPlanId) {
        MealPlan plan = mealPlanRepository
                .findById(mealPlanId)
                .orElseThrow(() -> new ApplicationException(
                        HttpStatus.NOT_FOUND, "no meal plan %s to derive a list from".formatted(mealPlanId)));

        List<PlannedIngredient> aggregated = aggregate(ingredientsOf(plan));
        shoppingListItemRepository.deleteByMealPlanId(mealPlanId);
        List<ShoppingListItem> items = aggregated.stream()
                .map(ingredient -> shoppingListItemMapper.toItem(ingredient, mealPlanId, plan.getUserId()))
                .toList();
        log.info("derived {} shopping list lines from plan {}", items.size(), mealPlanId);
        return shoppingListItemRepository.saveAll(items);
    }

    /**
     * A list that belongs to no weekly plan — the Friday-night snacks, the blackout lunch.
     *
     * <p>Kept as a separate method rather than a nullable plan id on the one above: the two have different lifetimes,
     * and an ad-hoc list must never be wiped by a plan regeneration.
     */
    @Transactional
    public List<ShoppingListItem> createAdHocList(UUID userId, List<PlannedIngredient> ingredients) {
        List<ShoppingListItem> items = aggregate(ingredients).stream()
                .map(ingredient -> shoppingListItemMapper.toItem(ingredient, null, userId))
                .toList();
        log.info("stored {} ad-hoc shopping list lines for user {}", items.size(), userId);
        return shoppingListItemRepository.saveAll(items);
    }

    /**
     * Whatever is on screen replaces whatever the user had before, ad-hoc or plan-derived: there is only ever one
     * live list per user. Without this, a weekly plan's list and a later {@code /list} answer can both sit in
     * {@code shopping_list_item} at once — invisible right up until an order merges both, sends the same product to
     * Silpo twice in one call, and the cart is refused outright.
     */
    @Transactional
    public void keepOnly(UUID userId, List<UUID> idsToKeep) {
        shoppingListItemRepository.deleteByUserIdAndIdNotIn(userId, idsToKeep);
    }

    /**
     * One line per ingredient and unit, quantities summed, original order and spelling kept.
     *
     * <p>Two lines of the same ingredient in different units — 2 шт цибулі and 200 г цибулі — stay two lines. Guessing
     * at a conversion would mean guessing the size of an onion, and a wrong guess is silently wrong in the basket. A
     * person reading two lines sees the problem immediately.
     */
    public static List<PlannedIngredient> aggregate(List<PlannedIngredient> ingredients) {
        Map<String, PlannedIngredient> byNameAndUnit = new LinkedHashMap<>();
        for (PlannedIngredient ingredient : ingredients) {
            if (ingredient == null
                    || ingredient.name() == null
                    || ingredient.name().isBlank()) {
                continue;
            }
            String key = normalise(ingredient.name()) + "|" + normalise(ingredient.unit());
            byNameAndUnit.merge(
                    key,
                    new PlannedIngredient(ingredient.name().trim(), ingredient.quantity(), ingredient.unit()),
                    ShoppingListService::add);
        }
        return List.copyOf(byNameAndUnit.values());
    }

    /** Keeps the first line's spelling and unit; only the quantity accumulates. */
    private static PlannedIngredient add(PlannedIngredient existing, PlannedIngredient extra) {
        BigDecimal quantity;
        if (existing.quantity() == null) {
            quantity = extra.quantity();
        } else if (extra.quantity() == null) {
            quantity = existing.quantity();
        } else {
            quantity = existing.quantity().add(extra.quantity());
        }
        return new PlannedIngredient(existing.name(), quantity, existing.unit());
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<PlannedIngredient> ingredientsOf(MealPlan plan) {
        WeeklyMealPlan week = MAPPER.convertValue(plan.getPlan(), WeeklyMealPlan.class);
        List<PlannedIngredient> ingredients = new ArrayList<>();
        for (PlannedDay day : week.days() == null ? List.<PlannedDay>of() : week.days()) {
            for (PlannedMeal meal : day.meals() == null ? List.<PlannedMeal>of() : day.meals()) {
                if (meal.ingredients() != null) {
                    ingredients.addAll(meal.ingredients());
                }
            }
        }
        return ingredients;
    }
}
