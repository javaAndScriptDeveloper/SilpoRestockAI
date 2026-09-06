package com.silporestockai.model;

import java.util.List;

/**
 * A week of meals, as it is stored in {@code meal_plan.plan_json}.
 *
 * <p>Deliberately carries no start date. The model does not know today, cannot know the household's timezone, and a
 * date it invented would disagree with the {@code meal_plan.week_start_date} column that everything downstream reads.
 * The service owns the date.
 *
 * <p>Two sources feed this shape. The recipe planner answers with a {@link RecipeWeek} — meal names plus one
 * purchase list — and is stored here with empty per-meal ingredients and that list under {@code shoppingList}.
 * The ready-meals planner (task 22) still names one real catalog product per meal, so its ingredients carry the
 * product ids and its {@code shoppingList} is null. {@code ShoppingListService} reads whichever is present.
 *
 * @param days one entry per weekday, {@code MONDAY} to {@code SUNDAY}
 * @param shoppingList the week's purchases, already aggregated — null for plans whose list is derived from the
 *     meals' ingredients (ready meals, and every plan stored before this field existed)
 */
public record WeeklyMealPlan(List<PlannedDay> days, List<PlannedIngredient> shoppingList) {

    /** The pre-purchase-list shape: a plan whose list is derived from its meals' ingredients. */
    public WeeklyMealPlan(List<PlannedDay> days) {
        this(days, null);
    }

    /** Whether this plan carries its own purchase list rather than per-meal ingredients. */
    public boolean hasShoppingList() {
        return shoppingList != null && !shoppingList.isEmpty();
    }
}
