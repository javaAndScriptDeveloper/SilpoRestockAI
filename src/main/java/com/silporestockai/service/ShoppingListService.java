package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.exception.ApplicationException;
import com.silporestockai.mapper.ShoppingListItemMapper;
import com.silporestockai.model.PlannedDay;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.PlannedMeal;
import com.silporestockai.model.ShoppingListSourceType;
import com.silporestockai.model.ShoppingListStatus;
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
 * Collapses a week of meals into the list somebody actually shops from, and owns every add/remove/quantity-change on
 * the live list.
 *
 * <p>This class never depends on {@link com.silporestockai.client.claude.ClaudeApiClient} — that is deliberate and
 * structural, not a convention to remember: every method here is either arithmetic ({@link #aggregate}) or plain CRUD
 * against {@code shopping_list_item}, and it must stay that way so "viewing or manually editing the list calls no AI"
 * is true by construction, not by discipline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListService {

    /** Own mapper, as elsewhere in the app: Boot 4 carries both Jackson 2 and Jackson 3. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final MealPlanRepository mealPlanRepository;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final ShoppingListItemMapper shoppingListItemMapper;
    private final CategoryKeywordFallbackService categoryKeywordFallbackService;

    /**
     * Derives the list for a weekly plan, archiving whatever the plan had before rather than deleting it — the old
     * rows stay as history a future delta feature can diff against.
     */
    @Transactional
    public List<ShoppingListItem> deriveFromMealPlan(UUID mealPlanId, ShoppingListSourceType sourceType) {
        MealPlan plan = mealPlanRepository
                .findById(mealPlanId)
                .orElseThrow(() -> new ApplicationException(
                        HttpStatus.NOT_FOUND, "no meal plan %s to derive a list from".formatted(mealPlanId)));

        List<PlannedIngredient> aggregated = aggregate(ingredientsOf(plan));
        shoppingListItemRepository.archiveActiveByMealPlanId(mealPlanId);
        List<ShoppingListItem> items = aggregated.stream()
                .map(this::withFallbackCategory)
                .map(ingredient -> shoppingListItemMapper.toItem(ingredient, mealPlanId, plan.getUserId()))
                .peek(item -> item.setSourceType(sourceType))
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
                .map(this::withFallbackCategory)
                .map(ingredient -> shoppingListItemMapper.toItem(ingredient, null, userId))
                .peek(item -> item.setSourceType(ShoppingListSourceType.RECIPE_DERIVED))
                .toList();
        log.info("stored {} ad-hoc shopping list lines for user {}", items.size(), userId);
        return shoppingListItemRepository.saveAll(items);
    }

    /**
     * Whatever is on screen replaces whatever the user had before, ad-hoc or plan-derived — there is only ever one
     * live list per user. Archives rather than deletes, same as {@link #deriveFromMealPlan}.
     */
    @Transactional
    public void keepOnly(UUID userId, List<UUID> idsToKeep) {
        shoppingListItemRepository.findByUserIdAndStatus(userId, ShoppingListStatus.ACTIVE).stream()
                .filter(item -> !idsToKeep.contains(item.getId()))
                .forEach(item -> item.setStatus(ShoppingListStatus.ARCHIVED));
        // JPA dirty-checking flushes the status changes above at commit; nothing further to save explicitly.
    }

    /** The user's live list, whichever flow produced it. */
    public List<ShoppingListItem> currentItems(UUID userId) {
        return shoppingListItemRepository.findByUserIdAndStatus(userId, ShoppingListStatus.ACTIVE);
    }

    /** The list on screen became a confirmed order. */
    @Transactional
    public void markOrdered(UUID userId) {
        shoppingListItemRepository.markOrderedByUserId(userId);
    }

    /** Adds one line directly — no AI call. */
    @Transactional
    public ShoppingListItem addItem(UUID userId, String name, BigDecimal quantity, String unit, String category) {
        ShoppingListItem item = ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .name(name)
                .quantity(quantity)
                .unit(unit)
                .category(
                        category == null || category.isBlank()
                                ? categoryKeywordFallbackService.categorize(name)
                                : category)
                .status(ShoppingListStatus.ACTIVE)
                .build();
        return shoppingListItemRepository.save(item);
    }

    /** Removes one line — no AI call. A foreign or already-inactive item id is a no-op. */
    @Transactional
    public void removeItem(UUID userId, UUID itemId) {
        shoppingListItemRepository.findById(itemId).ifPresent(item -> {
            if (item.getUserId().equals(userId) && item.getStatus() == ShoppingListStatus.ACTIVE) {
                shoppingListItemRepository.delete(item);
            }
        });
    }

    /** Changes one line's quantity — no AI call. A foreign or already-inactive item id is a no-op. */
    @Transactional
    public ShoppingListItem updateQuantity(UUID userId, UUID itemId, BigDecimal newQuantity) {
        return shoppingListItemRepository
                .findById(itemId)
                .filter(item -> item.getUserId().equals(userId) && item.getStatus() == ShoppingListStatus.ACTIVE)
                .map(item -> {
                    item.setQuantity(newQuantity);
                    return item;
                })
                .orElse(null);
    }

    private PlannedIngredient withFallbackCategory(PlannedIngredient ingredient) {
        if (ingredient.category() != null && !ingredient.category().isBlank()) {
            return ingredient;
        }
        return new PlannedIngredient(
                ingredient.name(),
                ingredient.quantity(),
                ingredient.unit(),
                categoryKeywordFallbackService.categorize(ingredient.name()),
                ingredient.productId());
    }

    /**
     * One line per ingredient and unit, quantities summed, original order, spelling and category kept.
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
                    new PlannedIngredient(
                            ingredient.name().trim(),
                            ingredient.quantity(),
                            ingredient.unit(),
                            ingredient.category(),
                            ingredient.productId()),
                    ShoppingListService::add);
        }
        return List.copyOf(byNameAndUnit.values());
    }

    /** Keeps the first line's spelling, unit and category; only the quantity accumulates. */
    private static PlannedIngredient add(PlannedIngredient existing, PlannedIngredient extra) {
        BigDecimal quantity;
        if (existing.quantity() == null) {
            quantity = extra.quantity();
        } else if (extra.quantity() == null) {
            quantity = existing.quantity();
        } else {
            quantity = existing.quantity().add(extra.quantity());
        }
        String category =
                existing.category() != null && !existing.category().isBlank() ? existing.category() : extra.category();
        return new PlannedIngredient(existing.name(), quantity, existing.unit(), category, existing.productId());
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
