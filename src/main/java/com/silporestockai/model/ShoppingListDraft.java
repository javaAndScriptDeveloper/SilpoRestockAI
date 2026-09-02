package com.silporestockai.model;

import java.util.List;

/**
 * A shopping list the user has not agreed to yet.
 *
 * <p>The same shape whether it came from a fridge photo, a receipt, a sentence or a weekly plan — everything
 * downstream only needs to know what to buy.
 *
 * @param items what to buy, with quantities somebody can sanity-check at a glance
 */
public record ShoppingListDraft(List<PlannedIngredient> items) {}
