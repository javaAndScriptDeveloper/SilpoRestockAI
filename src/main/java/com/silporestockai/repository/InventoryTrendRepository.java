package com.silporestockai.repository;

import com.silporestockai.entity.InventoryTrend;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Per-item consumption trend. */
public interface InventoryTrendRepository extends JpaRepository<InventoryTrend, UUID> {

    List<InventoryTrend> findByUserId(UUID userId);

    Optional<InventoryTrend> findByUserIdAndItemName(UUID userId, String itemName);

    /** Removal candidates. The threshold is the caller's, so it stays configurable rather than baked in. */
    List<InventoryTrend> findByUserIdAndConsecutiveUntouchedCyclesGreaterThanEqual(UUID userId, int threshold);
}
