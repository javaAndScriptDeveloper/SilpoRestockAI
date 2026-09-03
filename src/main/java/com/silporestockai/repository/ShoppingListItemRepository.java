package com.silporestockai.repository;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.ShoppingListStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Shopping list lines, either attached to a weekly plan or standing alone. */
public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, UUID> {

    List<ShoppingListItem> findByMealPlanId(UUID mealPlanId);

    /** The user's ad-hoc lines: everything they asked for outside a weekly plan. */
    List<ShoppingListItem> findByUserIdAndMealPlanIdIsNull(UUID userId);

    /** Whatever list is currently on screen, ad-hoc or derived from a weekly plan — there is only ever one live. */
    List<ShoppingListItem> findByUserId(UUID userId);

    /**
     * The user's list in one specific lifecycle state — callers name the state explicitly rather than rely on an
     * implicit "current" meaning, so a future status value can never silently leak into "the current list".
     */
    List<ShoppingListItem> findByUserIdAndStatus(UUID userId, ShoppingListStatus status);

    /** Regenerating a plan replaces its list wholesale rather than diffing it. */
    void deleteByMealPlanId(UUID mealPlanId);

    /** Whatever is being shown replaces whatever the user had before, regardless of which flow produced either. */
    void deleteByUserIdAndIdNotIn(UUID userId, Collection<UUID> ids);

    /** Moves this plan's live rows to ARCHIVED instead of deleting them, so there is history to diff against. */
    @Modifying
    @Query("update ShoppingListItem i set i.status = com.silporestockai.model.ShoppingListStatus.ARCHIVED "
            + "where i.mealPlanId = :mealPlanId and i.status = com.silporestockai.model.ShoppingListStatus.ACTIVE")
    void archiveActiveByMealPlanId(@Param("mealPlanId") UUID mealPlanId);

    /** Moves every ACTIVE row of this user (ad-hoc or plan-derived) to ARCHIVED. */
    @Modifying
    @Query("update ShoppingListItem i set i.status = com.silporestockai.model.ShoppingListStatus.ARCHIVED "
            + "where i.userId = :userId and i.status = com.silporestockai.model.ShoppingListStatus.ACTIVE")
    void archiveActiveByUserId(@Param("userId") UUID userId);

    /** Moves this user's ACTIVE rows to ORDERED — the list on screen became a confirmed order. */
    @Modifying
    @Query("update ShoppingListItem i set i.status = com.silporestockai.model.ShoppingListStatus.ORDERED "
            + "where i.userId = :userId and i.status = com.silporestockai.model.ShoppingListStatus.ACTIVE")
    void markOrderedByUserId(@Param("userId") UUID userId);
}
