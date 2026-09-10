package com.silporestockai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.BasketItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CartBuildingServiceTopUpTest {

    private static final BasketItem TOMATO = line("p-tomato", "Томат", "12.00");
    private static final BasketItem CABBAGE = line("p-cabbage", "Капуста червона", "18.00");
    private static final BasketItem SOUR_CREAM = line("p-sour", "Сметана Ферма 15%, стакан", "25.00");
    private static final BasketItem CHICKEN = line("p-chicken", "Куряче філе", "189.00");
    private static final List<BasketItem> BASELINE = List.of(CHICKEN, SOUR_CREAM, TOMATO, CABBAGE);

    @Test
    void reachesForTheCheapestLinesFirst() {
        List<BasketItem> eligible = CartBuildingService.eligibleForTopUp(BASELINE, Set.of(), Set.of());

        assertThat(eligible).containsExactly(TOMATO, CABBAGE, SOUR_CREAM, CHICKEN);
        assertThat(CartBuildingService.pickTopUp(eligible, new BigDecimal("742.29"), new BigDecimal("799")))
                .containsExactly(TOMATO, CABBAGE, SOUR_CREAM, CHICKEN);
    }

    @Test
    void aSecondRoundSkipsWhatTheBranchJustRanOutOfAndTakesTheNextLine() {
        // Live, session 25: the first round added the three cheapest lines, the read-back took all three out as
        // out of stock, and the second round picked the same three again — then dropped them, added nothing, and
        // left the cart ₴57 short with only «Скасувати» under it. The names just tried are now an exclusion.
        Set<String> tried = Set.of("Томат", "Капуста червона", "Сметана Ферма 15%, стакан");

        List<BasketItem> eligible = CartBuildingService.eligibleForTopUp(BASELINE, Set.of(), tried);

        assertThat(eligible).containsExactly(CHICKEN);
        assertThat(CartBuildingService.pickTopUp(eligible, new BigDecimal("742.29"), new BigDecimal("799")))
                .containsExactly(CHICKEN);
    }

    @Test
    void stopsOnceTheCartIsSafelyOverTheLine() {
        List<BasketItem> picked = CartBuildingService.pickTopUp(
                List.of(TOMATO, CABBAGE, SOUR_CREAM, CHICKEN), new BigDecimal("830"), new BigDecimal("799"));

        // 830 + 12 = 842 clears the 5 % margin over 799; nothing more is added on the household's behalf.
        assertThat(picked).containsExactly(TOMATO);
    }

    private static BasketItem line(String id, String name, String price) {
        return new BasketItem(id, name, "шт", BigDecimal.ONE, new BigDecimal(price));
    }
}
