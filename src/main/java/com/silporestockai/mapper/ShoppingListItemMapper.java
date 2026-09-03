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
 * line depending on where it came from, which is not something the mapper can know. {@code sourceType} is set by the
 * caller too, for the same reason.
 */
@Mapper
public interface ShoppingListItemMapper {

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "mealPlanId", source = "mealPlanId")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "category", source = "ingredient.category")
    @Mapping(target = "silpoProductId", source = "ingredient.productId")
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "sourceType", ignore = true)
    ShoppingListItem toItem(PlannedIngredient ingredient, UUID mealPlanId, UUID userId);
}
