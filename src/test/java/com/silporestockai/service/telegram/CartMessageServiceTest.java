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

    private static final OfferedSlot SLOT =
            new OfferedSlot("slot-1", "18:00 - 20:00", Instant.parse("2026-09-03T15:00:00Z"), null);

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

        assertThat(text)
                .contains("Цибуля")
                .contains("0.5 кг")
                .contains("25.50")
                .contains("Гречка")
                .contains("1 кг")
                .contains("Разом: 73.50 грн");
    }

    @Test
    void mentionsTheDeliverySlotOrSaysNoneIsChosenYet() {
        assertThat(service.cartText(twoItems(), SLOT, OrderType.INITIAL))
                .contains("Доставка:")
                .contains("18:00 - 20:00");
        assertThat(service.cartText(twoItems(), null, OrderType.INITIAL)).contains("слот ще не обрано");
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
