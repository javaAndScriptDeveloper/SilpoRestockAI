package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.utils.GroupEventSettings;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the organizer's one-line settings are parsed by label")
class GroupEventSettingsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);

    @Test
    void budgetAlone() {
        GroupEventSettings.Parsed parsed =
                GroupEventSettings.parse("бюджет 2000", TODAY).orElseThrow();
        assertThat(parsed.budget()).isEqualByComparingTo("2000");
        assertThat(parsed.eventTag()).isNull();
        assertThat(parsed.eventDate()).isNull();
    }

    @Test
    void allThreeWithSpacesAndCurrency() {
        GroupEventSettings.Parsed parsed = GroupEventSettings.parse(
                        "@komora_bot Бюджет: 1 500 грн, привід: Новий рік, дата 31.12", TODAY)
                .orElseThrow();
        assertThat(parsed.budget()).isEqualByComparingTo("1500");
        assertThat(parsed.eventTag()).isEqualTo("Новий рік");
        assertThat(parsed.eventDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void aDateAlreadyPassedThisYearMeansNextYear() {
        GroupEventSettings.Parsed parsed =
                GroupEventSettings.parse("дата 8.03", TODAY).orElseThrow();
        assertThat(parsed.eventDate()).isEqualTo(LocalDate.of(2027, 3, 8));
        assertThat(GroupEventSettings.parse("дата 08.03.27", TODAY)
                        .orElseThrow()
                        .eventDate())
                .isEqualTo(LocalDate.of(2027, 3, 8));
    }

    @Test
    void aPreferenceIsNotASetting() {
        assertThat(GroupEventSettings.parse("пиво світле, це на ДР", TODAY)).isEmpty();
        assertThat(GroupEventSettings.parse(".", TODAY)).isEmpty();
        assertThat(GroupEventSettings.parse("дата 31.02", TODAY)).isEmpty();
    }
}
