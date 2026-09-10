package com.silporestockai.model;

import java.util.List;

/**
 * What the household is entitled to on the cart in front of them right now (tasks 78 and 79).
 *
 * <p>Split by what the live API can actually do, not by what the documentation lists: {@link #certificates} and
 * {@link #promoCode} have real cart-mutation tools behind them and are offered for one tap, while {@link #coupons}
 * have none and are only ever mentioned. Bonuses are not here — they come back on the cart read itself, in
 * {@link CartSummary#bonusAvailable()}, so re-reading them would be a second answer to a question already answered.
 *
 * @param certificates gift certificates that could go on this cart
 * @param promoCode the promo code to offer, or null when the household has none
 * @param coupons active coupons worth mentioning, applied by Silpo itself at checkout and by nobody here
 */
public record CartBenefits(List<GiftCertificate> certificates, String promoCode, List<LoyaltyCoupon> coupons) {

    private static final CartBenefits NONE = new CartBenefits(List.of(), null, List.of());

    /** Nothing to offer — also what every failed read degrades to. */
    public static CartBenefits none() {
        return NONE;
    }

    /** Never null, whatever version of this record a stored conversation state came from. */
    public List<GiftCertificate> certificateList() {
        return certificates == null ? List.of() : certificates;
    }

    /** Never null, whatever version of this record a stored conversation state came from. */
    public List<LoyaltyCoupon> couponList() {
        return coupons == null ? List.of() : coupons;
    }

    /** Whether anything here can actually be put on the cart. */
    public boolean hasApplicable() {
        return !certificateList().isEmpty() || (promoCode != null && !promoCode.isBlank());
    }

    /** Whether there is anything at all to say — applicable or merely worth mentioning. */
    public boolean isEmpty() {
        return !hasApplicable() && couponList().isEmpty();
    }
}
