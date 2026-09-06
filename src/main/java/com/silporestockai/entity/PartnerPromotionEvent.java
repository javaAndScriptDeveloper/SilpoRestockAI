package com.silporestockai.entity;

import com.silporestockai.model.PartnerPromotionEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One step of one household through a promotion's funnel (task 46). The partner's report is a count over these. */
@Entity
@Table(name = "partner_promotion_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerPromotionEvent {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "promotion_id", nullable = false)
    private UUID promotionId;

    @Column(name = "user_id")
    private UUID userId;

    /** Set for {@code CONFIRMED_ORDER} only. */
    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private PartnerPromotionEventType eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
