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
    void multipliesABaselineUnitPriceByTheQuantityWhenTheUnitsMatch() {
        // Last time: 2 л of milk at 40 грн per litre — Silpo's cart line carries the unit price, the same number
        // the cart message multiplies. This week the list wants 3 л.
        BasketItem lastTime = new BasketItem("p-milk", "Молоко", "л", new BigDecimal("2"), new BigDecimal("40.00"));

        PriceEstimate estimate =
                ShoppingListPriceEstimateService.estimate(List.of(item("молоко", "3", "л")), List.of(lastTime));

        assertThat(estimate.total()).isEqualByComparingTo("120.00");
        assertThat(estimate.pricedCount()).isEqualTo(1);
    }

    @Test
    void neverReadsAPerKilogramPriceAsTheCostOfTheWholeLine() {
        // Live, 2026-09-11: 0.4 kg of salmon at ₴1399/kg in the baseline priced «Філе риби — 0.6 кг» at ₴2098 and
        // half a kilo of beetroot at ₴14.99/kg priced a kilo at ₴29.98 — the whole week read ₴4902 for two adults.
        BasketItem salmon = new BasketItem(
                "p-salmon",
                "Сьомга (лосось) філе охолоджене",
                "кг",
                new BigDecimal("0.4"),
                new BigDecimal("1399"),
                "Філе риби");
        BasketItem beet = new BasketItem("p-beet", "Буряк", "кг", new BigDecimal("0.5"), new BigDecimal("14.99"));

        PriceEstimate estimate = ShoppingListPriceEstimateService.estimate(
                List.of(item("Філе риби", "0.6", "кг"), item("Буряк", "1", "кг")), List.of(salmon, beet));

        assertThat(estimate.total()).isEqualByComparingTo("854.39");
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
        // Two bottles at 37.00 each last time; a count answers with that line cost, not with 37 × the new count.
        BasketItem lastTime = new BasketItem(
                "p-milk", "Молоко «Яготинське» 2,6% п/е", "шт", new BigDecimal("2"), new BigDecimal("37.00"), "молоко");

        PriceEstimate estimate =
                ShoppingListPriceEstimateService.estimate(List.of(item("Молоко", "1", "шт")), List.of(lastTime));

        assertThat(estimate.total()).isEqualByComparingTo("74.00");
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
                new BigDecimal("23.99"));

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

    @Test
    void doesNotMultiplyAPackagePriceByAHouseholdsCountOfWhatIsInside() {
        // Live, 2026-09-08: a list asking for «Яйця — 20 шт» against a baseline pack of eggs at ₴129.80 for
        // «1 шт» produced ₴2596 — two thirds of the whole estimate, from one line. Both units read «шт» and
        // neither counts the same thing: the household counts eggs, the catalog counts packs.
        BasketItem eggs = new BasketItem(
                "p-eggs",
                "Яйця курячі «Ясенсвіт» «Для духмяних пирогів» 1 категорії",
                "шт",
                new BigDecimal("1"),
                new BigDecimal("129.80"));

        PriceEstimate estimate =
                ShoppingListPriceEstimateService.estimate(List.of(item("Яйця", "20", "шт")), List.of(eggs));

        assertThat(estimate.total()).isEqualByComparingTo("129.80");
    }

    @Test
    void answersACountWithLastTimesLineCostEvenForTheSameProduct() {
        // The pairing says this is the same product, but «2 шт» of bread against «1 шт» bought last time is still
        // a count against a package count; what the household paid for the line is the honest nearest number.
        BasketItem bread = new BasketItem(
                "p-bread",
                "Хліб «Премія»® Фітнес тостовий",
                "шт",
                new BigDecimal("1"),
                new BigDecimal("34.99"),
                "Хліб");

        PriceEstimate estimate =
                ShoppingListPriceEstimateService.estimate(List.of(item("Хліб", "2", "шт")), List.of(bread));

        assertThat(estimate.total()).isEqualByComparingTo("34.99");
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
