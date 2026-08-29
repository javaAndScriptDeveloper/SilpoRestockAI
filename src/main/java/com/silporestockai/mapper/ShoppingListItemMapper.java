package com.silporestockai.mapper;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.PlannedIngredient;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Turns an aggregated ingredient into a shopping list line.
 *
 * <p>Ownership — which plan, which user — is set by the caller: the same ingredient becomes a plan line or an ad-hoc
 * line depending on where it came from, which is not something the mapper can know.
 */
@Mapper
public interface ShoppingListItemMapper {

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "mealPlanId", source = "mealPlanId")
    @Mapping(target = "userId", source = "userId")
    // Category is a Silpo notion, filled in by product matching (task 09), not by the plan.
    @Mapping(target = "category", ignore = true)
    ShoppingListItem toItem(PlannedIngredient ingredient, UUID mealPlanId, UUID userId);
}
