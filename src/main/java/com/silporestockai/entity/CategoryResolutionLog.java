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

/**
 * One resolved shopping-list line — the denominator of Featured Share Rate (task 63).
 *
 * <p>Every line a flow resolves lands here, promoted or not. Task 46's event tables only ever see the lines a
 * placement won, so on their own they can say «featured four times» and never «four out of how many».
 *
 * <p>Attribution is by {@code resolvedProductId}, which is exact. There is deliberately no brand column: a brand
 * parsed out of «Молоко «Яготинське» 2,6% п/е» would be a guess, and a guess in the numerator corrupts every share
 * computed from it.
 */
@Entity
@Table(name = "category_resolution_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryResolutionLog {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The line as the household asked it, e.g. «Молоко» — matched against a promotion's category word. */
    @Column(name = "line_name", nullable = false, length = 256)
    private String lineName;

    @Column(name = "resolved_product_id", nullable = false, length = 64)
    private String resolvedProductId;

    @Column(name = "resolved_product_name", length = 256)
    private String resolvedProductName;

    /** The placement that answered this line, or null for an ordinary match. */
    @Column(name = "promotion_id")
    private UUID promotionId;

    /** How many plausible candidates the catalog returned for the line; null when the second pass rescued it. */
    @Column(name = "candidate_count")
    private Integer candidateCount;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
