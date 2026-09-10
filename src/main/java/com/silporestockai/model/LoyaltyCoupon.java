package com.silporestockai.model;

/**
 * One coupon from {@code silpo_get_my_coupons}.
 *
 * <p>A coupon is the one loyalty mechanism the live MCP server offers no way to apply: every one of the 40 tools was
 * searched for an argument that takes a coupon id, barcode or promoId, and only the two read tools mention coupons at
 * all. So this record exists to be <em>shown</em>, honestly and with its real terms, never to back an "apply" button
 * that would do nothing on Silpo's side.
 *
 * @param id the {@code businessCouponId} {@code silpo_get_coupon_details} is keyed by
 * @param title what the coupon is for, in Silpo's words
 * @param rewardText the reward as Silpo renders it, e.g. {@code -15%}
 * @param endDate the last day it can be used, as Silpo wrote it
 * @param active the household's own on/off toggle in the Silpo app
 * @param canBeApplied {@code canBeAppliedToOrder} from the details call — eligibility, which needs both the toggle
 *     and the lifecycle state; null when the details call was not made or did not answer
 * @param limitText the conditions in Silpo's own prose, or null
 * @param progressText an accumulation threshold already converted to display scale («1200 з 2500 грн»), or null when
 *     this coupon does not track one
 */
public record LoyaltyCoupon(
        long id,
        String title,
        String rewardText,
        String endDate,
        boolean active,
        Boolean canBeApplied,
        String limitText,
        String progressText) {

    /** «-15% — на покупку», or whichever half of that Silpo actually sent. */
    public String label() {
        if (rewardText == null || rewardText.isBlank()) {
            return title == null ? "купон" : title;
        }
        return title == null || title.isBlank() ? rewardText : rewardText + " — " + title;
    }
}
