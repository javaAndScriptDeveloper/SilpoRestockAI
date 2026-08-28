package com.silporestockai.entity;

import com.silporestockai.model.SpecialMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * How a household eats.
 *
 * <p>Most columns are nullable because the profile is filled in progressively: some of it arrives from the Silpo
 * profile over MCP during onboarding, the rest only when a conversation happens to reveal it.
 */
@Entity
@Table(name = "user_profile")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class UserProfile {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "household_size")
    private Integer householdSize;

    @Column(name = "has_kids")
    private Boolean hasKids;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "kids_ages")
    private List<Integer> kidsAges;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dietary_restrictions")
    private List<String> dietaryRestrictions;

    @Column(name = "weekly_budget", precision = 10, scale = 2)
    private BigDecimal weeklyBudget;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "disliked_foods")
    private List<String> dislikedFoods;

    @Column(name = "only_ua_producer", nullable = false)
    @Builder.Default
    private Boolean onlyUaProducer = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "special_mode", length = 64)
    private SpecialMode specialMode;

    @Column(name = "special_mode_started_at")
    private Instant specialModeStartedAt;
}
