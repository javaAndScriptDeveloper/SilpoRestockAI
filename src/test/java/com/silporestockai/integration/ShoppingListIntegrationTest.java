package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ShoppingListService;
import com.silporestockai.service.UserAccountService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("a stored weekly plan becomes stored shopping list lines")
class ShoppingListIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MealPlanRepository mealPlanRepository;

    @Autowired
    private ShoppingListItemRepository shoppingListItemRepository;

    @BeforeEach
    void clean() {
        shoppingListItemRepository.deleteAll();
        mealPlanRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** Two days that share an onion, plus one ingredient the model gave in a different unit. */
    private MealPlan persistedPlan(long chatId) {
        User user = userAccountService.findOrCreate(chatId);
        Map<String, Object> plan = Map.of(
                "days",
                List.of(
                        Map.of(
                                "day",
                                "MONDAY",
                                "meals",
                                List.of(
                                        meal(
                                                "Борщ",
                                                ingredient("цибуля", "0.2", "кг"),
                                                ingredient("буряк", "0.5", "кг")),
                                        meal("Омлет", ingredient("яйця", "6", "шт")),
                                        meal("Гречка", ingredient("гречка", "0.4", "кг")))),
                        Map.of(
                                "day",
                                "TUESDAY",
                                "meals",
                                List.of(
                                        meal("Суп", ingredient("цибуля", "0.3", "кг")),
                                        meal("Салат", ingredient("цибуля", "2", "шт")),
                                        meal("Каша", ingredient("гречка", "0.1", "кг"))))));
        return mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(LocalDate.of(2026, 8, 31))
                .plan(plan)
                .createdAt(Instant.now())
                .build());
    }

    private static Map<String, Object> meal(String name, Map<String, Object>... ingredients) {
        return Map.of("type", "LUNCH", "name", name, "ingredients", List.of(ingredients));
    }

    private static Map<String, Object> ingredient(String name, String quantity, String unit) {
        return Map.of("name", name, "quantity", new BigDecimal(quantity), "unit", unit);
    }

    private ShoppingListItem lineFor(List<ShoppingListItem> items, String name, String unit) {
        return items.stream()
                .filter(item -> item.getName().equals(name) && item.getUnit().equals(unit))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for %s in %s".formatted(name, unit)));
    }

    @Test
    void sumsAnIngredientThatAppearsInSeveralMealsAndKeepsMismatchedUnitsApart() {
        MealPlan plan = persistedPlan(8301L);

        List<ShoppingListItem> items = shoppingListService.deriveFromMealPlan(plan.getId());

        assertThat(lineFor(items, "цибуля", "кг").getQuantity()).isEqualByComparingTo("0.5");
        assertThat(lineFor(items, "цибуля", "шт").getQuantity()).isEqualByComparingTo("2");
        assertThat(lineFor(items, "гречка", "кг").getQuantity()).isEqualByComparingTo("0.5");
        assertThat(items).hasSize(5);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getMealPlanId()).isEqualTo(plan.getId());
            assertThat(item.getUserId()).isEqualTo(plan.getUserId());
        });
    }

    @Test
    void derivingTwiceReplacesTheListRatherThanDoublingIt() {
        MealPlan plan = persistedPlan(8302L);
        shoppingListService.deriveFromMealPlan(plan.getId());

        shoppingListService.deriveFromMealPlan(plan.getId());

        assertThat(shoppingListItemRepository.findByMealPlanId(plan.getId())).hasSize(5);
    }

    @Test
    void anAdHocListBelongsToAUserAndToNoPlan() {
        UUID userId = userAccountService.findOrCreate(8303L).getId();

        List<ShoppingListItem> items = shoppingListService.createAdHocList(
                userId,
                List.of(
                        new PlannedIngredient("попкорн", new BigDecimal("2"), "шт", null),
                        new PlannedIngredient("попкорн", new BigDecimal("1"), "шт", null),
                        new PlannedIngredient("кола", new BigDecimal("1.5"), "л", null)));

        assertThat(items).hasSize(2);
        assertThat(shoppingListItemRepository.findByUserIdAndMealPlanIdIsNull(userId))
                .hasSize(2);
        assertThat(lineFor(items, "попкорн", "шт").getQuantity()).isEqualByComparingTo("3");
        assertThat(items).allSatisfy(item -> assertThat(item.getMealPlanId()).isNull());
    }

    @Test
    void aPlanRegenerationDoesNotTouchAnAdHocList() {
        MealPlan plan = persistedPlan(8304L);
        UUID userId = plan.getUserId();
        shoppingListService.createAdHocList(
                userId, List.of(new PlannedIngredient("морозиво", BigDecimal.ONE, "шт", null)));

        shoppingListService.deriveFromMealPlan(plan.getId());

        assertThat(shoppingListItemRepository.findByUserIdAndMealPlanIdIsNull(userId))
                .hasSize(1);
    }
}
