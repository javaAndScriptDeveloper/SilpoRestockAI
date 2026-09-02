package com.silporestockai.repository;

import com.silporestockai.entity.ShoppingListItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Shopping list lines, either attached to a weekly plan or standing alone. */
public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, UUID> {

    List<ShoppingListItem> findByMealPlanId(UUID mealPlanId);

    /** The user's ad-hoc lines: everything they asked for outside a weekly plan. */
    List<ShoppingListItem> findByUserIdAndMealPlanIdIsNull(UUID userId);

    /** Whatever list is currently on screen, ad-hoc or derived from a weekly plan — there is only ever one live. */
    List<ShoppingListItem> findByUserId(UUID userId);

    /** Regenerating a plan replaces its list wholesale rather than diffing it. */
    void deleteByMealPlanId(UUID mealPlanId);
}
