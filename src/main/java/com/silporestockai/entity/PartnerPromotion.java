package com.silporestockai.entity;

import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.model.PromotionType;
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

/**
 * A paid featured placement (task 46): when a household's list needs {@code categoryOrQuery}, this partner's real
 * product is the match — provided Silpo still returns it live and the household's restrictions allow it.
 *
 * <p>{@code silpoProductId} and {@code productName} are what the catalog answered when the promotion was created,
 * never a free-text description trusted blindly (the same grounding discipline as task 22).
 */
@Entity
@Table(name = "partner_promotion")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerPromotion {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "partner_name", nullable = false, length = 128)
    private String partnerName;

    /** What triggers featuring — a category word the list line has to contain, e.g. «молоко». */
    @Column(name = "category_or_query", nullable = false, length = 128)
    private String categoryOrQuery;

    @Column(name = "silpo_product_id", nullable = false, length = 64)
    private String silpoProductId;

    /** The product's name as the catalog has it — also the search term that re-verifies it on every cart build. */
    @Column(name = "product_name", nullable = false, length = 256)
    private String productName;

    /** Higher wins when two active promotions match the same line. */
    @Column(name = "priority_weight", nullable = false)
    @Builder.Default
    private int priorityWeight = 100;

    /** Paid external placement, or Silpo's own margin lever — the report never blends the two (task 63). */
    @Enumerated(EnumType.STRING)
    @Column(name = "promotion_type", nullable = false, length = 32)
    @Builder.Default
    private PromotionType promotionType = PromotionType.PAID_PARTNER;

    @Column(name = "active_from")
    private Instant activeFrom;

    @Column(name = "active_to")
    private Instant activeTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PartnerPromotionStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
