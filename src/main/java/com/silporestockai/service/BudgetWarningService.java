package com.silporestockai.service;

import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.UserProfileRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The one place that compares what a household said it wanted to spend against what the basket actually costs
 * (task 66).
 *
 * <p>Both numbers were already in hand and never met: {@code user_profile.weekly_budget} comes from onboarding
 * (task 20), the sum from task 39's estimate or from the built cart. No MCP call, no new column — this is arithmetic
 * on two figures the system had all along.
 *
 * <p>It speaks only when the sum is over. A household that stayed inside its budget does not need to be told so
 * before every order, and «ти в межах бюджету!» on twelve carts in a row is the kind of noise that teaches people
 * to stop reading the message that also carries «Не знайшов: …». Nothing here blocks anything: the warning is a
 * sentence above the same «Підтвердити» that was always there.
 */
@Service
@RequiredArgsConstructor
public class BudgetWarningService {

    private final UserProfileRepository userProfileRepository;

    /** The message as it will be sent, with the warning added when there is one to add. */
    public String appendTo(String message, UUID userId, BigDecimal total) {
        return appendTo(message, userId, total, false);
    }

    /**
     * The same, told whether benefits are still waiting to be applied to this cart (tasks 78 and 79).
     *
     * <p>The sum being compared is the one printed above the warning, which on a cart with bonuses, a certificate
     * or a promo code still to apply is not the sum that will be charged. Naming that in the same breath is the
     * difference between a warning and a wrong number: the household is about to tap a button that lowers it.
     */
    public String appendTo(String message, UUID userId, BigDecimal total, boolean benefitsPending) {
        String warning = warningLine(total, weeklyBudget(userId), benefitsPending);
        return warning == null ? message : message + "\n\n" + warning;
    }

    /** The stored weekly budget, or null when this household never gave one. */
    public BigDecimal weeklyBudget(UUID userId) {
        return userProfileRepository
                .findByUserId(userId)
                .map(UserProfile::getWeeklyBudget)
                .orElse(null);
    }

    /**
     * The rule itself, kept static so a test needs no repository.
     *
     * <p>Silent unless the sum is genuinely over a budget that genuinely exists. A profile with no budget is the
     * ordinary case for older households and for anyone who left the field empty in the Анкета — it is not an error
     * and must not read like one, so it produces nothing rather than a comparison against zero.
     *
     * @return the line to show, or null when there is nothing to say
     */
    public static String warningLine(BigDecimal total, BigDecimal weeklyBudget, boolean benefitsPending) {
        if (total == null || weeklyBudget == null || weeklyBudget.signum() <= 0) {
            return null;
        }
        BigDecimal over = total.subtract(weeklyBudget);
        if (over.signum() <= 0) {
            return null;
        }
        StringBuilder line = new StringBuilder("⚠ Це на ")
                .append(money(over))
                .append(" грн більше за твій тижневий бюджет (")
                .append(amount(weeklyBudget))
                .append(" грн).");
        if (benefitsPending) {
            // Said before the household reads the difference as final: one tap below spends bonuses, a certificate
            // or a promo code against exactly this sum.
            line.append(" Частину покриють вигоди вище — тоді різниця буде меншою.");
        }
        return line.append(" Замовляємо, чи щось прибрати?").toString();
    }

    /** Two decimals, as every other money line in the bot: a price with one reads as a typo. */
    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** The budget as the household typed it: «2500», not «2500.00». */
    private static String amount(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }
}
