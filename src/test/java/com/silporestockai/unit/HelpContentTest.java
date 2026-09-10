package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.service.telegram.HelpContent;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole point of {@link HelpContent} is that «Інструкція» and the onboarding reveal (task 70) cannot drift
 * apart, so the test that matters is the containment one: every line the reveal shows is a line the button shows.
 */
@DisplayName("the help copy has exactly one source")
class HelpContentTest {

    @Test
    void theButtonStillRendersTheWholeInstruction() {
        assertThat(HelpContent.FULL)
                .startsWith("Кнопки внизу:")
                .contains("❓ Інструкція — це повідомлення.")
                .contains("Усе інше — просто напиши. Наприклад:")
                .contains("— «Голова після вчорашнього» — мінералка й сорбенти, найближча доставка.")
                .contains("— «Підключи Google Календар» — вноситиму доставки в календар.")
                .contains("Компанією: додай мене в груповий чат");
    }

    @Test
    void everyLineTheRevealShowsIsALineTheInstructionShows() {
        List<String> revealed = List.of(
                HelpContent.EXAMPLE_AD_HOC_ORDER,
                HelpContent.EXAMPLE_LIST_EDIT,
                HelpContent.EXAMPLE_DISH,
                HelpContent.EXAMPLE_SPECIAL_MODE,
                HelpContent.EXAMPLE_BLACKOUT);
        for (String line : revealed) {
            assertThat(HelpContent.REVEAL).contains(line);
            assertThat(HelpContent.FULL).contains(line);
        }
    }

    /**
     * Task 74: the pitch is a shared order for a company, and «напої» appears only where it tells the truth
     * about what the round resolves today — never in the headline that sells the feature.
     */
    @Test
    void theGroupPitchIsAnOrderForACompanyNotADrinkingMode() {
        String pitch = HelpContent.FULL.substring(HelpContent.FULL.indexOf("Компанією:"));
        // The pitch is one paragraph; its first sentence is what sells the feature.
        String headline = pitch.substring(0, pitch.indexOf('.') + 1);

        assertThat(headline).contains("спільну закупку").doesNotContain("напо");
        assertThat(pitch).contains("Поки що збираю напої");
    }

    /** The reveal is the teaser, not the reference — it must stay short and point at the full list. */
    @Test
    void theRevealIsShorterThanTheInstructionAndPointsAtIt() {
        assertThat(HelpContent.REVEAL.length()).isLessThan(HelpContent.FULL.length());
        assertThat(HelpContent.REVEAL).contains("❓ Інструкція");
        assertThat(HelpContent.REVEAL).doesNotContain("Кнопки внизу:");
    }
}
