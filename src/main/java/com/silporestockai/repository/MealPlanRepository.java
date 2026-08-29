package com.silporestockai.repository;

import com.silporestockai.entity.MealPlan;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Weekly meal plans. */
public interface MealPlanRepository extends JpaRepository<MealPlan, UUID> {

    /**
     * The current plan for a week. Regeneration inserts a second row for the same week, so "current" is the newest
     * one — a plain {@code findByUserIdAndWeekStartDate} returning {@code Optional} would throw.
     */
    Optional<MealPlan> findFirstByUserIdAndWeekStartDateOrderByCreatedAtDesc(UUID userId, LocalDate weekStartDate);

    /** The most recent plan, which is what regeneration and reorder logic start from. */
    Optional<MealPlan> findFirstByUserIdOrderByWeekStartDateDesc(UUID userId);
}
