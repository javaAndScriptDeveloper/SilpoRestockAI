package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.PriceEstimate;
import com.silporestockai.service.ShoppingListPriceEstimateService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShoppingListPriceEstimateServiceTest {

    @Test
    void multipliesACatalogUnitPriceByTheQuantity() {
        ShoppingListItem plov = item("Плов з куркою готовий", "3", "порція");
        plov.setEstimatedPrice(new BigDecimal("89.90"));

        PriceEstimate estimate = ShoppingListPriceEstimateService.estimate(List.of(plov), List.of());

        assertThat(estimate.total()).isEqualByComparingTo("269.70");
        assertThat(estimate.pricedCount()).isEqualTo(1);
        assertThat(estimate.unpricedCount()).isZero();
        assertThat(estimate.isPartial()).isFalse();
    }

    @Test
    void scalesABaselineLinePriceWhenTheUnitsMatch() {
        // Last time: 2 л of milk for 80 грн. This week the list wants 3 л.
        BasketItem lastTime = new BasketItem("p-milk", "Молоко", "л", new BigDecimal("2"), new BigDecimal("80.00"));

        PriceEstimate estimate =
                ShoppingListPriceEstimateService.estimate(List.of(item("молоко", "3", "л")), List.of(lastTime));

        assertThat(estimate.total()).isEqualByComparingTo("120.00");
        assertThat(estimate.pricedCount()).isEqualTo(1);
    }

    @Test
    void usesTheBaselineLinePriceAsIsWhenTheUnitsDiffer() {
        BasketItem lastTime = new BasketItem("p-onion", "Цибуля", "кг", new BigDecimal("1"), new BigDecimal("30.00"));

        PriceEstimate estimate =
                ShoppingListPriceEstimateService.estimate(List.of(item("Цибуля", "5", "шт")), List.of(lastTime));

        // No invented kg-per-onion conversion: the old line price is the honest nearest number.
        assertThat(estimate.total()).isEqualByComparingTo("30.00");
    }

    @Test
    void countsLinesNoSourceCanPriceAndReportsNoneWhenThatIsAllOfThem() {
        ShoppingListItem priced = item("Плов з куркою готовий", "1", "порція");
        priced.setEstimatedPrice(new BigDecimal("89.90"));

        PriceEstimate partial =
                ShoppingListPriceEstimateService.estimate(List.of(priced, item("Шафран", "1", "г")), List.of());
        PriceEstimate nothing = ShoppingListPriceEstimateService.estimate(List.of(item("Шафран", "1", "г")), List.of());

        assertThat(partial.total()).isEqualByComparingTo("89.90");
        assertThat(partial.pricedCount()).isEqualTo(1);
        assertThat(partial.unpricedCount()).isEqualTo(1);
        assertThat(partial.isPartial()).isTrue();
        assertThat(nothing.hasPrices()).isFalse();
    }

    @Test
    void pricesAListLineFromTheProductTheSameLineBoughtLastTime() {
        BasketItem lastTime = new BasketItem(
                "p-milk", "Молоко «Яготинське» 2,6% п/е", "шт", new BigDecimal("2"), new BigDecimal("74.00"), "молоко");

        PriceEstimate estimate =
                ShoppingListPriceEstimateService.estimate(List.of(item("Молоко", "1", "шт")), List.of(lastTime));

        assertThat(estimate.total()).isEqualByComparingTo("37.00");
        assertThat(estimate.pricedCount()).isEqualTo(1);
    }

    @Test
    void fallsBackToTheCatalogNameContainingEveryWordOfTheListLine() {
        // What the live baselines actually hold: Silpo's catalog names against household words.
        BasketItem onion =
                new BasketItem("p-onion", "Цибуля ріпчаста жовта", "кг", new BigDecimal("1"), new BigDecimal("21.99"));
        BasketItem potato = new BasketItem(
                "p-potato",
                "Картопля Сенсейшн універсальна, для смаження та варіння",
                "кг",
                new BigDecimal("2"),
                new BigDecimal("47.98"));

        PriceEstimate estimate = ShoppingListPriceEstimateService.estimate(
                List.of(item("Цибуля", "1", "кг"), item("Картопля", "1", "кг")), List.of(onion, potato));

        assertThat(estimate.total()).isEqualByComparingTo("45.98");
        assertThat(estimate.pricedCount()).isEqualTo(2);
    }

    @Test
    void refusesAMatchThatIsMissingAWordOrIsOnlyPartOfOne() {
        BasketItem chicken = new BasketItem(
                "p-chicken",
                "Філе курчати-бройлера мале охолоджене",
                "кг",
                new BigDecimal("1"),
                new BigDecimal("270.41"));
        BasketItem loaf = new BasketItem(
                "p-loaf", "Батон «Київхліб» нарізний", "шт", new BigDecimal("1"), new BigDecimal("32.90"));

        // «куряче» is nowhere in the catalog name, and «хліб» is inside «Київхліб» rather than a word of its own.
        // Both stay unpriced: a number the household reads as real must not be built out of near-misses.
        PriceEstimate estimate = ShoppingListPriceEstimateService.estimate(
                List.of(item("Куряче філе", "1", "кг"), item("Хліб", "2", "шт")), List.of(chicken, loaf));

        assertThat(estimate.hasPrices()).isFalse();
        assertThat(estimate.unpricedCount()).isEqualTo(2);
    }

    @Test
    void prefersTheLineThatNamesTheRequestOverAnEarlierOneThatMerelyContainsTheWord() {
        BasketItem baked = new BasketItem(
                "p-baked", "Молоко згущене варене", "шт", new BigDecimal("1"), new BigDecimal("89.00"), null);
        BasketItem milk = new BasketItem(
                "p-milk", "Молоко «Яготинське» 2,6% п/е", "шт", new BigDecimal("1"), new BigDecimal("37.00"), "молоко");

        // Baseline order would have handed this to the condensed milk; the recorded request outranks it.
        PriceEstimate estimate =
                ShoppingListPriceEstimateService.estimate(List.of(item("Молоко", "1", "шт")), List.of(baked, milk));

        assertThat(estimate.total()).isEqualByComparingTo("37.00");
    }

    private static ShoppingListItem item(String name, String quantity, String unit) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .quantity(new BigDecimal(quantity))
                .unit(unit)
                .build();
    }
}
