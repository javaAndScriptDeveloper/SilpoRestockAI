package com.silporestockai.repository;

import com.silporestockai.entity.MealPlan;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Weekly meal plans. */
public interface MealPlanRepository extends JpaRepository<MealPlan, UUID> {

    Optional<MealPlan> findByUserIdAndWeekStartDate(UUID userId, LocalDate weekStartDate);

    /** The most recent plan, which is what regeneration and reorder logic start from. */
    Optional<MealPlan> findFirstByUserIdOrderByWeekStartDateDesc(UUID userId);
}
