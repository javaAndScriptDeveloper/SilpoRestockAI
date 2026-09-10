package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.SpecialMode;
import org.junit.jupiter.api.Test;

/**
 * Task 67: the whole override, which is one method. The point of testing it here rather than only through the
 * planner is that this is the invariant — the stored preference is read, never written.
 */
class CrunchWeekOverrideTest {

    @Test
    void aCrunchWeekReadsAsReadyMealsWhateverTheHouseholdStored() {
        UserProfile profile = profile(CookingTimePreference.COOKS_DAILY, SpecialMode.CRUNCH_WEEK);

        assertThat(profile.effectiveCookingTimePreference()).isEqualTo(CookingTimePreference.READY_MEALS_ONLY);
        // The column itself is untouched — which is what makes the revert free.
        assertThat(profile.getCookingTimePreference()).isEqualTo(CookingTimePreference.COOKS_DAILY);
    }

    @Test
    void withNoCrunchWeekTheStoredPreferenceIsTheEffectiveOne() {
        assertThat(profile(CookingTimePreference.COOKS_BATCH, SpecialMode.NONE).effectiveCookingTimePreference())
                .isEqualTo(CookingTimePreference.COOKS_BATCH);
        assertThat(profile(CookingTimePreference.COOKS_DAILY, null).effectiveCookingTimePreference())
                .isEqualTo(CookingTimePreference.COOKS_DAILY);
    }

    /** Another mode owns the diet, not the stove: a gastritis week still plans as the household cooks. */
    @Test
    void anotherSpecialModeDoesNotTouchTheCookingDimension() {
        assertThat(profile(CookingTimePreference.COOKS_DAILY, SpecialMode.MEDICAL_GASTRITIS_ACUTE)
                        .effectiveCookingTimePreference())
                .isEqualTo(CookingTimePreference.COOKS_DAILY);
    }

    /** A profile that never answered the cooking question stays unanswered — a crunch week is not an answer to it. */
    @Test
    void aProfileWithNoStoredPreferenceStillReadsAsReadyMealsDuringACrunchWeek() {
        assertThat(profile(null, SpecialMode.CRUNCH_WEEK).effectiveCookingTimePreference())
                .isEqualTo(CookingTimePreference.READY_MEALS_ONLY);
        assertThat(profile(null, SpecialMode.NONE).effectiveCookingTimePreference())
                .isNull();
    }

    private static UserProfile profile(CookingTimePreference stored, SpecialMode mode) {
        return UserProfile.builder()
                .cookingTimePreference(stored)
                .specialMode(mode)
                .build();
    }
}
