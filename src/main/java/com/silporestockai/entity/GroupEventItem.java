package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line the group agreed on (task 68), written at consensus.
 *
 * <p>This is the history: the next round in this company reads it as «what we took last time», and a round of
 * similar size around the same date reads it as the seasonal average. {@code resolvedSilpoProductId} is null
 * for a line the catalog did not have — the name is still worth remembering.
 */
@Entity
@Table(name = "group_event_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupEventItem {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "group_event_id", nullable = false)
    private UUID groupEventId;

    @Column(name = "resolved_silpo_product_id", length = 64)
    private String resolvedSilpoProductId;

    @Column(name = "product_name", nullable = false, length = 256)
    private String productName;

    @Column(name = "requested_name", length = 256)
    private String requestedName;

    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;
}
