package com.silporestockai.entity;

import com.silporestockai.model.AgeBracket;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.DietType;
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

    @Column(name = "special_mode_expires_at")
    private Instant specialModeExpiresAt;

    @Column(name = "target_weight_kg", precision = 5, scale = 2)
    private BigDecimal targetWeightKg;

    @Column(name = "target_calories")
    private Integer targetCalories;

    @Column(name = "target_protein_g")
    private Integer targetProteinG;

    @Column(name = "adult_male_count")
    private Integer adultMaleCount;

    @Column(name = "adult_female_count")
    private Integer adultFemaleCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "children_age_brackets")
    private List<AgeBracket> childrenAgeBrackets;

    @Enumerated(EnumType.STRING)
    @Column(name = "diet_type", length = 32)
    private DietType dietType;

    @Enumerated(EnumType.STRING)
    @Column(name = "cooking_time_preference", length = 32)
    private CookingTimePreference cookingTimePreference;

    /** When the one-time capability teaser (task 70) went out. Null means it has not. */
    @Column(name = "capability_reveal_sent_at")
    private Instant capabilityRevealSentAt;

    /** Where a friend's gift may be delivered (task 81). Null unless this household opted in. */
    @Column(name = "gift_delivery_address", length = 512)
    private String giftDeliveryAddress;

    /** The number a courier delivering that gift should call. Stored with the address, never separately. */
    @Column(name = "gift_delivery_phone", length = 32)
    private String giftDeliveryPhone;

    /**
     * Whether a friend naming this household by {@code @nickname} may have a gift sent here without being asked
     * (task 81). False for every profile that has not said otherwise, including every profile that existed
     * before the column did.
     */
    @Column(name = "gift_address_shareable", nullable = false)
    @Builder.Default
    private Boolean giftAddressShareable = false;

    /**
     * Whether a gift can be sent here with no exchange at all.
     *
     * <p>The flag alone is not enough: an address cleared afterwards would otherwise resolve to nothing at the
     * one moment it is needed, which is halfway through building somebody's cart.
     */
    public boolean acceptsGifts() {
        return Boolean.TRUE.equals(giftAddressShareable)
                && giftDeliveryAddress != null
                && !giftDeliveryAddress.isBlank();
    }

    /**
     * How this household should be planned for right now, which is not always how they said they cook (task 67).
     *
     * <p>{@link SpecialMode#CRUNCH_WEEK} is a deadline, not a change of habit, so it must not leave a mark on the
     * profile: a household that cooks daily and had one bad fortnight would otherwise come out of it recorded as
     * ready-meals people, and nothing in the app would ever put that back. The override lives here, at the point
     * of reading, and {@code cooking_time_preference} keeps whatever the Анкета stored — which is also what makes
     * the revert free: end the mode and this method answers with the real preference again, with nothing to
     * restore.
     *
     * <p>Every planner reads this rather than the column. The getter still exists for the one thing that legitimately
     * wants the stored value: the Анкета prefill, which is editing the stored value.
     */
    public CookingTimePreference effectiveCookingTimePreference() {
        return specialMode == SpecialMode.CRUNCH_WEEK ? CookingTimePreference.READY_MEALS_ONLY : cookingTimePreference;
    }
}
