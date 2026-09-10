package com.silporestockai.service.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.AppliedBenefits;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartBenefits;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.GiftCertificate;
import com.silporestockai.model.LoyaltyCoupon;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.TelegramButton;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a household reads about the benefits on their cart (tasks 78 and 79): one offer covering everything Silpo
 * can actually apply, and a plain mention — never a button — for the coupons it cannot.
 */
@DisplayName("the benefits shown beside a cart")
class CartBenefitsMessageTest {

    private static final OfferedSlot SLOT =
            new OfferedSlot("slot-1", "2026-09-03T18:00:00Z", Instant.parse("2026-09-03T18:00:00Z"), null);

    private final CartMessageService service = new CartMessageService();

    private static CartSummary cart(BigDecimal bonus, boolean bonusPending) {
        return new CartSummary(
                "cart-1",
                "slot-1",
                Instant.parse("2026-09-03T15:00:00Z"),
                List.of(new BasketItem("p-1", "Молоко", "шт", BigDecimal.ONE, new BigDecimal("42.90"))),
                new BigDecimal("912.00"),
                List.of(),
                bonus,
                bonusPending,
                "https://silpo.ua/checkout/cart-1",
                "silpo://checkout/cart-1",
                List.of());
    }

    private static CartBenefits everything() {
        return new CartBenefits(
                List.of(new GiftCertificate("9001234321", "1111", new BigDecimal("500"), "2026-12-31")),
                "SUMMER10",
                List.of());
    }

    @Test
    void offersOneExtraButtonThatCoversEveryAvailableBenefit() {
        CartSummary summary = cart(new BigDecimal("250"), true);

        List<TelegramButton> buttons = service.cartButtons(summary, true, everything());

        assertThat(buttons)
                .extracting(TelegramButton::callbackData)
                .containsExactly(
                        CartMessageService.CALLBACK_CONFIRM,
                        CartMessageService.CALLBACK_CONFIRM_BENEFITS,
                        CartMessageService.CALLBACK_SLOT_MENU,
                        CartMessageService.CALLBACK_CANCEL);
        assertThat(buttons.get(1).label()).isEqualTo("Підтвердити + вигоди");
    }

    @Test
    void listsEveryBenefitInTheCartTextSoTheTapIsInformed() {
        String text = service.cartText(cart(new BigDecimal("250"), true), SLOT, OrderType.INITIAL, everything());

        assertThat(text)
                .contains("Твої вигоди")
                .contains("250")
                .contains("Сертифікат")
                .contains("…4321")
                .contains("500")
                .contains("SUMMER10");
    }

    @Test
    void keepsTodaysWordingWhenBonusesAreTheOnlyBenefit() {
        List<TelegramButton> buttons =
                service.cartButtons(cart(new BigDecimal("250"), true), false, CartBenefits.none());

        assertThat(buttons)
                .extracting(TelegramButton::callbackData)
                .containsExactly(
                        CartMessageService.CALLBACK_CONFIRM,
                        CartMessageService.CALLBACK_CONFIRM_BONUS,
                        CartMessageService.CALLBACK_CANCEL);
        assertThat(buttons.get(1).label()).isEqualTo("Підтвердити + 250 бонусів");
    }

    @Test
    void saysNothingAtAllWhenThereIsNoBenefitToOffer() {
        CartSummary noBonuses = cart(BigDecimal.ZERO, false);

        assertThat(service.cartButtons(noBonuses, false, CartBenefits.none()))
                .extracting(TelegramButton::callbackData)
                .containsExactly(CartMessageService.CALLBACK_CONFIRM, CartMessageService.CALLBACK_CANCEL);
        assertThat(service.cartText(noBonuses, SLOT, OrderType.INITIAL, CartBenefits.none()))
                .doesNotContain("вигоди");
    }

    /** A certificate with no bonuses beside it still earns the offer — the button is not a bonus button. */
    @Test
    void offersTheBenefitsButtonForACertificateAlone() {
        CartBenefits certificateOnly = new CartBenefits(
                List.of(new GiftCertificate("9001234321", "1111", new BigDecimal("500"), null)), null, List.of());

        assertThat(service.cartButtons(cart(BigDecimal.ZERO, false), false, certificateOnly))
                .extracting(TelegramButton::callbackData)
                .containsExactly(
                        CartMessageService.CALLBACK_CONFIRM,
                        CartMessageService.CALLBACK_CONFIRM_BENEFITS,
                        CartMessageService.CALLBACK_CANCEL);
    }

    /**
     * The live API has no way to apply a coupon, so the cart mentions it and stops there. A button here would be a
     * promise nothing on Silpo's side could keep.
     */
    @Test
    void mentionsAnActiveCouponWithoutOfferingToApplyIt() {
        CartBenefits couponOnly = new CartBenefits(
                List.of(),
                null,
                List.of(new LoyaltyCoupon(
                        1L,
                        "на покупку",
                        "-15%",
                        "2026-10-03",
                        true,
                        true,
                        "Максимальна сума знижки — 150 грн",
                        null)));
        CartSummary noBonuses = cart(BigDecimal.ZERO, false);

        assertThat(service.cartText(noBonuses, SLOT, OrderType.INITIAL, couponOnly))
                .contains("-15%")
                .contains("«Сільпо»");
        assertThat(service.cartButtons(noBonuses, false, couponOnly))
                .extracting(TelegramButton::callbackData)
                .containsExactly(CartMessageService.CALLBACK_CONFIRM, CartMessageService.CALLBACK_CANCEL);
    }

    /**
     * A cart under Silpo's minimum has no confirm button, so no benefits offer either — but the coupon note still
     * belongs there, because that message sends the household to the Silpo app, which is where a coupon works.
     */
    @Test
    void keepsTheCouponNoteOnACartThatCannotCheckOutYet() {
        CartSummary tooSmall = new CartSummary(
                "cart-1",
                "slot-1",
                Instant.parse("2026-09-03T15:00:00Z"),
                List.of(new BasketItem("p-1", "Вода", "шт", BigDecimal.ONE, new BigDecimal("42.90"))),
                new BigDecimal("141.90"),
                List.of(),
                BigDecimal.ZERO,
                false,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                new BigDecimal("42.90"),
                new BigDecimal("799"));
        CartBenefits withCoupon = new CartBenefits(
                List.of(new GiftCertificate("9001234321", "1", new BigDecimal("500"), null)),
                "SUMMER10",
                List.of(new LoyaltyCoupon(1L, "на покупку", "-15%", "2026-10-03", true, true, null, null)));

        String text = service.belowMinimumText(tooSmall, SLOT, OrderType.AD_HOC, true, withCoupon);

        assertThat(text).contains("-15%").contains("бракує");
        // No offer without a confirm button to attach it to.
        assertThat(text).doesNotContain("Твої вигоди").doesNotContain("SUMMER10");
    }

    @Test
    void tellsTheHouseholdExactlyWhatSilpoTookAndWhatItRefused() {
        AppliedBenefits applied = new AppliedBenefits(
                new BigDecimal("250"),
                List.of("9001234321"),
                "SUMMER10",
                List.of("Промокод WINTER5 «Сільпо» не прийняло."),
                new BigDecimal("162.00"));

        String text = service.appliedBenefitsText(applied);

        assertThat(text).contains("250").contains("SUMMER10").contains("162.00").contains("WINTER5");
    }

    @Test
    void saysNothingWhenNothingWasApplied() {
        assertThat(service.appliedBenefitsText(AppliedBenefits.none())).isNull();
    }
}
