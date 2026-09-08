package com.silporestockai.model;

import java.util.List;

/**
 * What the ready-meals planner answers with: seven days of choices, each a position in the candidate list it was
 * shown — never a name, a price or a product id.
 *
 * <p>The previous shape was the full {@link WeeklyMealPlan}: twenty-one meals, each carrying a six-field ingredient
 * object with the catalog name copied character for character, plus a shopping list the schema also offered. Live on
 * 2026-09-08 that answer ran past the 120 s timeout twice in a row and the household saw nothing. A position is a few
 * tokens; the code turns it back into the real product, the same way {@code ProductMatchingService} does.
 */
public record ReadyMealWeek(List<ReadyMealDay> days) {}
