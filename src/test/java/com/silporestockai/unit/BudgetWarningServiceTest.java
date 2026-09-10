package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.service.BudgetWarningService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Task 66: the one comparison, and every reason it stays quiet. */
class BudgetWarningServiceTest {

    @Test
    void namesTheExactDifferenceWhenTheCartIsOverBudget() {
        String line = BudgetWarningService.warningLine(new BigDecimal("2820.50"), new BigDecimal("2500.00"), false);

        assertThat(line)
                .isEqualTo(
                        "⚠ Це на 320.50 грн більше за твій тижневий бюджет (2500 грн). Замовляємо, чи щось прибрати?");
    }

    @Test
    void saysNothingWhenTheCartIsWithinBudget() {
        assertThat(BudgetWarningService.warningLine(new BigDecimal("2499.99"), new BigDecimal("2500.00"), false))
                .isNull();
    }

    @Test
    void saysNothingWhenTheCartLandsExactlyOnTheBudget() {
        assertThat(BudgetWarningService.warningLine(new BigDecimal("2500.00"), new BigDecimal("2500"), false))
                .isNull();
    }

    @Test
    void saysNothingWhenTheHouseholdNeverGaveABudget() {
        assertThat(BudgetWarningService.warningLine(new BigDecimal("9999.00"), null, false))
                .isNull();
    }

    @Test
    void saysNothingWhenTheStoredBudgetIsZeroOrNegative() {
        assertThat(BudgetWarningService.warningLine(new BigDecimal("9999.00"), BigDecimal.ZERO, false))
                .isNull();
        assertThat(BudgetWarningService.warningLine(new BigDecimal("9999.00"), new BigDecimal("-10"), false))
                .isNull();
    }

    @Test
    void saysNothingWhenThereIsNoTotalToCompare() {
        assertThat(BudgetWarningService.warningLine(null, new BigDecimal("2500"), false))
                .isNull();
    }

    @Test
    void warnsThatBenefitsWillLowerTheDifferenceWhenSomeAreStillPending() {
        String line = BudgetWarningService.warningLine(new BigDecimal("2700"), new BigDecimal("2500"), true);

        assertThat(line).contains("Частину покриють вигоди вище — тоді різниця буде меншою.");
        assertThat(line).startsWith("⚠ Це на 200.00 грн більше за твій тижневий бюджет (2500 грн).");
    }
}
