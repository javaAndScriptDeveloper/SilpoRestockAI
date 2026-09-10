package com.silporestockai.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * What Silpo actually took off a cart when the household tapped «Підтвердити + вигоди» (tasks 78 and 79).
 *
 * <p>Every field is what happened, not what was offered. Silpo accepts each mechanism independently — a certificate
 * can be refused while a promo code lands — and a household told they spent something they did not would be the one
 * failure worth avoiding here at any cost.
 *
 * @param bonuses the bonuses Silpo agreed to spend, or null when none were applied
 * @param certificates the barcodes Silpo accepted
 * @param promoCode the promo code Silpo accepted, or null
 * @param refusals plain-word notes about what did not go through, to be read out rather than swallowed
 * @param newTotal the cart total read back after all of it, or null when the cart could not be re-read
 */
public record AppliedBenefits(
        BigDecimal bonuses, List<String> certificates, String promoCode, List<String> refusals, BigDecimal newTotal) {

    private static final AppliedBenefits NONE = new AppliedBenefits(null, List.of(), null, List.of(), null);

    /** Nothing was asked for, or nothing was applied. */
    public static AppliedBenefits none() {
        return NONE;
    }

    public List<String> certificateList() {
        return certificates == null ? List.of() : certificates;
    }

    public List<String> refusalList() {
        return refusals == null ? List.of() : refusals;
    }

    public boolean bonusesApplied() {
        return bonuses != null && bonuses.signum() > 0;
    }

    /** Whether Silpo took anything at all. */
    public boolean anythingApplied() {
        return bonusesApplied() || !certificateList().isEmpty() || (promoCode != null && !promoCode.isBlank());
    }

    /** Whether there is anything to tell the household — a success or a refusal both count. */
    public boolean worthSaying() {
        return anythingApplied() || !refusalList().isEmpty();
    }
}
