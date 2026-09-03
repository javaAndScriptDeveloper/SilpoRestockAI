package com.silporestockai;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.service.ShoppingListService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The aggregation is pure arithmetic over the plan, so it is tested without Spring, a database or Claude. */
@DisplayName("ingredients across a week collapse into one line per name and unit")
class ShoppingListAggregationTest {

    private static PlannedIngredient of(String name, String quantity, String unit) {
        return new PlannedIngredient(name, quantity == null ? null : new BigDecimal(quantity), unit, null, null);
    }

    private static PlannedIngredient of(String name, String quantity, String unit, String category) {
        return new PlannedIngredient(name, quantity == null ? null : new BigDecimal(quantity), unit, category, null);
    }

    private static PlannedIngredient of(String name, String quantity, String unit, String category, String productId) {
        return new PlannedIngredient(name, quantity == null ? null : new BigDecimal(quantity), unit, category, productId);
    }

    @Test
    void keepsTheProductIdWhenTheSameReadyMealIsPickedTwice() {
        List<PlannedIngredient> aggregated = ShoppingListService.aggregate(List.of(
                of("Плов з куркою готовий", "1", "порція", "Готові страви", "p-42"),
                of("Плов з куркою готовий", "1", "порція", "Готові страви", "p-42")));

        assertThat(aggregated).hasSize(1);
        assertThat(aggregated.getFirst().productId()).isEqualTo("p-42");
        assertThat(aggregated.getFirst().quantity()).isEqualByComparingTo("2");
    }

    @Test
    void sumsTheSameIngredientAcrossEveryMealThatUsesIt() {
        List<PlannedIngredient> aggregated = ShoppingListService.aggregate(List.of(
                of("цибуля", "0.2", "кг"),
                of("гречка", "0.4", "кг"),
                of("цибуля", "0.3", "кг"),
                of("цибуля", "0.5", "кг"),
                of("цибуля", "0.1", "кг")));

        assertThat(aggregated).hasSize(2);
        assertThat(aggregated.getFirst().name()).isEqualTo("цибуля");
        assertThat(aggregated.getFirst().quantity()).isEqualByComparingTo("1.1");
        assertThat(aggregated.getFirst().unit()).isEqualTo("кг");
    }

    @Test
    void keepsTheSameIngredientInDifferentUnitsApart() {
        List<PlannedIngredient> aggregated =
                ShoppingListService.aggregate(List.of(of("цибуля", "2", "шт"), of("цибуля", "0.2", "кг")));

        assertThat(aggregated).hasSize(2);
        assertThat(aggregated).extracting(PlannedIngredient::unit).containsExactlyInAnyOrder("шт", "кг");
    }

    @Test
    void treatsCaseAndSpacingAsTheSameIngredient() {
        List<PlannedIngredient> aggregated = ShoppingListService.aggregate(
                List.of(of("Молоко", "1", "л"), of("  молоко ", "2", "Л"), of("МОЛОКО", "1", "л")));

        assertThat(aggregated).hasSize(1);
        // The first spelling survives: the list is read by a person, and "Молоко" is how they wrote it.
        assertThat(aggregated.getFirst().name()).isEqualTo("Молоко");
        assertThat(aggregated.getFirst().quantity()).isEqualByComparingTo("4");
    }

    @Test
    void keepsAnIngredientWhoseQuantityTheModelOmitted() {
        List<PlannedIngredient> aggregated =
                ShoppingListService.aggregate(List.of(of("сіль", null, "г"), of("сіль", "10", "г")));

        assertThat(aggregated).hasSize(1);
        assertThat(aggregated.getFirst().quantity()).isEqualByComparingTo("10");
    }

    @Test
    void dropsNothingAndInventsNothingForAnEmptyPlan() {
        assertThat(ShoppingListService.aggregate(List.of())).isEmpty();
    }

    @Test
    void ignoresAnIngredientWithoutAName() {
        List<PlannedIngredient> aggregated =
                ShoppingListService.aggregate(List.of(of(" ", "1", "кг"), of("рис", "1", "кг")));

        assertThat(aggregated).hasSize(1);
        assertThat(aggregated.getFirst().name()).isEqualTo("рис");
    }

    @Test
    void aggregationKeepsTheFirstNonBlankCategoryForRepeatedIngredients() {
        List<PlannedIngredient> aggregated = ShoppingListService.aggregate(
                List.of(of("Цибуля", "1", "шт", "Овочі і фрукти"), of("Цибуля", "2", "шт", null)));

        assertThat(aggregated).hasSize(1);
        assertThat(aggregated.getFirst().category()).isEqualTo("Овочі і фрукти");
        assertThat(aggregated.getFirst().quantity()).isEqualByComparingTo("3");
    }
}
