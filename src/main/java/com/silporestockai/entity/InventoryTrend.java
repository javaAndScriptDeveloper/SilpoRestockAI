package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * How many check-in cycles in a row an item has gone untouched.
 *
 * <p>Deliberately approximate. This tracks a trend so the agent can stop suggesting things nobody eats; it is not a
 * stock-counting system.
 */
@Entity
@Table(name = "inventory_trend")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class InventoryTrend {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "consecutive_untouched_cycles", nullable = false)
    @Builder.Default
    private int consecutiveUntouchedCycles = 0;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;
}
