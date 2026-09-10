package com.silporestockai.service.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.BasketItem;
import com.silporestockai.model.CartBenefits;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.OfferedSlot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the sender of a gift reads — which is everything except where it is going. */
@DisplayName("the cart a household is sending to somebody else")
class GiftCartMessageTest {

    private static final OfferedSlot SLOT = new OfferedSlot(
            "2026-09-11T07:30:00Z",
            "2026-09-11T07:30:00Z",
            Instant.parse("2026-09-11T07:30:00Z"),
            "2026-09-11T09:00:00Z");

    private final CartMessageService service = new CartMessageService();

    private static CartSummary cart() {
        return new CartSummary(
                "cart-1",
                "2026-09-11T07:30:00Z",
                Instant.parse("2026-09-10T15:00:00Z"),
                List.of(new BasketItem("p-1", "Кава Lavazza", "шт", BigDecimal.ONE, new BigDecimal("249.00"))),
                new BigDecimal("899.00"),
                List.of(),
                BigDecimal.ZERO,
                false,
                "https://silpo.ua/checkout/cart-1",
                "silpo://checkout/cart-1",
                List.of());
    }

    @Test
    void namesTheFriendAndNeverTheAddress() {
        String text = service.giftCartText(cart(), SLOT, "@olena", CartBenefits.none());

        assertThat(text).contains("@olena").contains("Кава Lavazza");
        assertThat(text).doesNotContain("Хрещатик").doesNotContain("вулиц").doesNotContain("+380");
    }

    @Test
    void saysItIsAGiftSoTheConfirmButtonIsNotMistakenForTheWeeklyOrder() {
        assertThat(service.giftCartText(cart(), SLOT, "@olena", CartBenefits.none()))
                .contains("Подарунок")
                .contains("Зібрав подарунок");
    }
}
