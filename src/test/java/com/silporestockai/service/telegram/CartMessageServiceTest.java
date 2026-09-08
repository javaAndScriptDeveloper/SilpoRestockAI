package com.silporestockai.service.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.model.OrderType;
import com.silporestockai.model.TelegramButton;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the cart a person reads before tapping confirm")
class CartMessageServiceTest {

    private final CartMessageService service = new CartMessageService();

    private static CartSummary summary(
            List<BasketItem> items, BigDecimal total, BigDecimal bonus, boolean pending, List<String> unresolved) {
        return new CartSummary(
                "cart-1",
                "slot-1",
                Instant.parse("2026-09-03T15:00:00Z"),
                items,
                total,
                List.of(),
                bonus,
                pending,
                "https://silpo.ua/checkout/cart-1",
                "silpo://checkout/cart-1",
                unresolved);
    }

    /**
     * A line the sanity check held back is named with its reason, apart from the lines Silpo simply had nothing
     * for — the person should know a ₴3246 line was dropped on purpose, not lost.
     */
    @Test
    void namesTheLinesHeldBackBySanityCheckSeparatelyFromTheUnfound() {
        CartSummary summary = new CartSummary(
                "cart-1",
                "slot-1",
                Instant.parse("2026-09-03T15:00:00Z"),
                List.of(new BasketItem("p-2", "Гречка", "кг", BigDecimal.ONE, new BigDecimal("48"))),
                new BigDecimal("48"),
                List.of(),
                BigDecimal.ZERO,
                false,
                "https://silpo.ua/checkout/cart-1",
                "silpo://checkout/cart-1",
                List.of("Банан"),
                List.of(),
                List.of(
                        "Яловичина — 34 шт «Яловичина Objerky в'ялена» — схоже, не той розмір упаковки, перевір позицію"));

        String text = service.cartText(summary, SLOT, OrderType.INITIAL);

        assertThat(text)
                .contains("Не знайшов: Банан")
                .contains("Не поклав, бо виглядає неправильно:\n— Яловичина — 34 шт");
    }

    private static final OfferedSlot SLOT = new OfferedSlot(
            "slot-1", "2026-09-03T15:00:00+00:00", Instant.parse("2026-09-03T15:00:00Z"), "2026-09-03T16:30:00+00:00");

    private static CartSummary twoItems() {
        return summary(
                List.of(
                        new BasketItem("p-1", "Цибуля", "кг", new BigDecimal("0.5"), new BigDecimal("25.5")),
                        new BasketItem("p-2", "Гречка", "кг", BigDecimal.ONE, new BigDecimal("48"))),
                new BigDecimal("73.5"),
                BigDecimal.ZERO,
                false,
                List.of());
    }

    @Test
    void listsEveryItemWithItsQuantityAndTheTotal() {
        String text = service.cartText(twoItems(), SLOT, OrderType.INITIAL);

        // A line says what it costs, not the price per kilogram beside a fraction of one: half a kilo of onion at
        // ₴25.50 a kilo is ₴12.75.
        assertThat(text)
                .contains("Цибуля — 0.5 кг — 12.75 грн")
                .contains("Гречка — 1 кг — 48.00 грн")
                .contains("Разом: 73.50 грн");
    }

    @Test
    void mentionsTheDeliverySlotOrSaysNoneIsChosenYet() {
        // The slot's own label is raw ISO from Silpo; the person reads the window in Kyiv time.
        assertThat(service.cartText(twoItems(), SLOT, OrderType.INITIAL)).contains("Доставка: чт, 3 вер · 18:00–19:30");
        assertThat(service.cartText(twoItems(), null, OrderType.INITIAL)).contains("час ще не обрано");
        assertThat(service.slotButtons(List.of(SLOT)).getFirst().label()).isEqualTo("чт, 3 вер · 18:00–19:30");
    }

    /** Silpo's own promotion total shows as a saving; a cart with none says nothing about it. */
    @Test
    void namesSilposOwnSavingsWhenThereAreAny() {
        CartSummary withSavings = new CartSummary(
                "cart-1",
                "slot-1",
                Instant.parse("2026-09-03T15:00:00Z"),
                twoItems().items(),
                new BigDecimal("73.5"),
                List.of(),
                BigDecimal.ZERO,
                false,
                "https://silpo.ua/checkout/cart-1",
                "silpo://checkout/cart-1",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new BigDecimal("34.5"));

        assertThat(service.cartText(withSavings, SLOT, OrderType.AD_HOC)).contains("Економія за акціями: 34.50 грн");
        assertThat(service.cartText(twoItems(), SLOT, OrderType.AD_HOC)).doesNotContain("Економія");
    }

    /**
     * Task 62: a partner placement is an internal matter. The line the household reads must be indistinguishable
     * from any other line — no marker on it, no disclosure paragraph under the list.
     */
    @Test
    void readsIdenticallyWhetherOrNotALineCameFromAPartnerPlacement() {
        List<BasketItem> items = List.of(
                new BasketItem("p-1", "Молоко «Яготинське» 2,6% п/е", "шт", BigDecimal.ONE, new BigDecimal("42.90")),
                new BasketItem("p-2", "Гречка", "кг", BigDecimal.ONE, new BigDecimal("48")));
        CartSummary plain = new CartSummary(
                "cart-1",
                "slot-1",
                Instant.parse("2026-09-03T15:00:00Z"),
                items,
                new BigDecimal("90.90"),
                List.of(),
                BigDecimal.ZERO,
                false,
                "https://silpo.ua/checkout/cart-1",
                "silpo://checkout/cart-1",
                List.of(),
                List.of());
        CartSummary promoted = new CartSummary(
                "cart-1",
                "slot-1",
                Instant.parse("2026-09-03T15:00:00Z"),
                items,
                new BigDecimal("90.90"),
                List.of(),
                BigDecimal.ZERO,
                false,
                "https://silpo.ua/checkout/cart-1",
                "silpo://checkout/cart-1",
                List.of(),
                List.of("p-1"));

        String text = service.cartText(promoted, SLOT, OrderType.INITIAL);

        assertThat(text).isEqualTo(service.cartText(plain, SLOT, OrderType.INITIAL));
        assertThat(text).doesNotContain("★").doesNotContain("партнер");
    }

    @Test
    void flagsWhatSilpoCouldNotMatchInsteadOfHidingIt() {
        CartSummary cart = summary(
                twoItems().items(), new BigDecimal("73.5"), BigDecimal.ZERO, false, List.of("трюфелі", "хамон"));

        assertThat(service.cartText(cart, SLOT, OrderType.INITIAL))
                .contains("Не знайшов")
                .contains("трюфелі")
                .contains("хамон");
    }

    @Test
    void offersConfirmAndCancelAndNothingElseWhenThereAreNoBonusesOrAlternativeSlots() {
        List<TelegramButton> buttons = service.cartButtons(twoItems(), false);

        assertThat(buttons)
                .extracting(TelegramButton::callbackData)
                .containsExactly(CartMessageService.CALLBACK_CONFIRM, CartMessageService.CALLBACK_CANCEL);
    }

    @Test
    void offersAnotherTimeButtonOnlyWhenThereAreAlternativeSlots() {
        List<TelegramButton> buttons = service.cartButtons(twoItems(), true);

        assertThat(buttons)
                .extracting(TelegramButton::callbackData)
                .containsExactly(
                        CartMessageService.CALLBACK_CONFIRM,
                        CartMessageService.CALLBACK_SLOT_MENU,
                        CartMessageService.CALLBACK_CANCEL);
    }

    @Test
    void asksTheBonusQuestionAsAThirdButtonThatNamesTheAmount() {
        CartSummary cart = summary(twoItems().items(), new BigDecimal("73.5"), new BigDecimal("120"), true, List.of());

        List<TelegramButton> buttons = service.cartButtons(cart, false);

        assertThat(buttons)
                .extracting(TelegramButton::callbackData)
                .containsExactly(
                        CartMessageService.CALLBACK_CONFIRM,
                        CartMessageService.CALLBACK_CONFIRM_BONUS,
                        CartMessageService.CALLBACK_CANCEL);
        assertThat(buttons.get(1).label()).contains("120");
    }

    @Test
    void theClosingMessageSaysWhereToPayAndWhetherBonusesWereSpent() {
        CartSummary cart = summary(twoItems().items(), new BigDecimal("73.5"), new BigDecimal("120"), true, List.of());

        assertThat(service.confirmedText(cart, true, OrderType.INITIAL))
                .contains("120")
                .contains("https://silpo.ua/checkout/cart-1");
        assertThat(service.confirmedText(cart, false, OrderType.INITIAL)).doesNotContain("Списав бонусів");
    }

    @Test
    void onlyTheFirstOrderClaimsToHaveBecomeTheBaseline() {
        CartSummary cart = twoItems();

        // CartConfirmationService.confirm stores a baseline for INITIAL only; the wording must not promise more.
        assertThat(service.confirmedText(cart, false, OrderType.INITIAL)).contains("еталонний набір");
        assertThat(service.confirmedText(cart, false, OrderType.AD_HOC))
                .doesNotContain("Зберіг цей кошик як еталонний")
                .contains("лишаю як був");
    }

    @Test
    void anAdHocCartIsNotCalledAWeeklyOne() {
        assertThat(service.cartText(twoItems(), SLOT, OrderType.INITIAL)).startsWith("Зібрав кошик на тиждень");
        assertThat(service.cartText(twoItems(), SLOT, OrderType.AD_HOC))
                .startsWith("Зібрав кошик:")
                .doesNotContain("на тиждень");
    }

    @Test
    void theCheckoutButtonOpensTheMobileDeepLinkNotTheWebPage() {
        CartSummary cart = summary(twoItems().items(), new BigDecimal("73.5"), BigDecimal.ZERO, false, List.of());

        assertThat(service.checkoutButtons(cart))
                .extracting(TelegramButton::url)
                .containsExactly("silpo://checkout/cart-1");
        assertThat(service.checkoutButtons(cart).getFirst().label()).isEqualTo("Перейти до оплати");
    }

    @Test
    void theFallbackLineMentionsTheWebLinkNotTheMobileOne() {
        CartSummary cart = summary(twoItems().items(), new BigDecimal("73.5"), BigDecimal.ZERO, false, List.of());

        assertThat(service.checkoutFallbackLine(cart))
                .contains("https://silpo.ua/checkout/cart-1")
                .doesNotContain("silpo://checkout/cart-1");
    }

    @Test
    void survivesACartWhoseLinesCarryNoPriceOrQuantity() {
        CartSummary cart = summary(
                List.of(new BasketItem("p-3", "Молоко", null, null, null)),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                List.of());

        assertThat(service.cartText(cart, SLOT, OrderType.INITIAL)).contains("Молоко");
        assertThat(service.checkoutButtons(summary(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, false, List.of())))
                .isNotNull();
    }
}
