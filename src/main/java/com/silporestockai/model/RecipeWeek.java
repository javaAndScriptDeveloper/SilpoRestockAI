package com.silporestockai.model;

import java.util.List;

/**
 * What the recipe planner is asked to answer with: a week of meal <em>names</em>, and one purchase list for the
 * whole week — nothing else.
 *
 * <p>This replaced asking for every meal's ingredient objects. That shape was about a hundred five-field objects
 * for a 21-meal week, ran past the output token cap on a live account (a two-adult household got «План скласти не
 * вдалось» three times in a row, each attempt two minutes long), and produced a list of recipe-gram sums — «Мед
 * 20 г», «Борошно 100 г» — that nobody can buy. Asking the model for the purchase list directly is shorter, faster,
 * and lets it do the one thing a person would do at this step: round to what the shop actually sells.
 *
 * <p>No {@code productId}, no {@code price}: the schema offers the model nothing to fabricate. Silpo's own ids are
 * stamped on by the catalog, never here.
 *
 * @param days one entry per weekday, {@code MONDAY} to {@code SUNDAY}
 * @param shoppingList the week's purchases, one line per product, in shop-sized quantities
 */
public record RecipeWeek(List<RecipeDay> days, List<PurchaseLine> shoppingList) {}
