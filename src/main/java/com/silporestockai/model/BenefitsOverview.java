package com.silporestockai.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything Silpo's «Лояльність та акції» tools say about a household, for the «які в мене вигоди?» answer
 * (task 79).
 *
 * <p>The split that matters is not by tool but by what can be done: bonuses, certificates and promo codes have real
 * cart-mutation tools behind them and are applied at confirmation time, while coupons, personal promos and the
 * Premium subscription have none anywhere in the 40 live tools and are only ever read. The message says which is
 * which, in those words, so nobody is left expecting an automation that cannot exist.
 *
 * @param bonusBalance the loyalty balance, or null when the balance could not be read
 * @param certificates gift certificates the household owns
 * @param promoCodes promo codes on the account
 * @param coupons every coupon, switched on or not — this surface reports, so it does not filter
 * @param promos personal promo offers, as Silpo words them
 * @param premiumSummary the Плюхс status line Silpo sent, or null when it could not be read
 * @param premiumLinks the subscribe or share links Silpo asks to be shown alongside that status
 * @param anythingRead whether a single one of the calls answered — an account with nothing is not the same fact as
 *     a server that said nothing, and the two must never read alike
 */
public record BenefitsOverview(
        BigDecimal bonusBalance,
        List<GiftCertificate> certificates,
        List<String> promoCodes,
        List<LoyaltyCoupon> coupons,
        List<String> promos,
        String premiumSummary,
        List<String> premiumLinks,
        boolean anythingRead) {

    /** Nothing answered at all. */
    public static BenefitsOverview unreadable() {
        return new BenefitsOverview(null, List.of(), List.of(), List.of(), List.of(), null, List.of(), false);
    }

    /** Whether the household holds anything at all worth naming. */
    public boolean hasAnything() {
        return (bonusBalance != null && bonusBalance.signum() > 0)
                || !certificates.isEmpty()
                || !promoCodes.isEmpty()
                || !coupons.isEmpty()
                || !promos.isEmpty();
    }
}
