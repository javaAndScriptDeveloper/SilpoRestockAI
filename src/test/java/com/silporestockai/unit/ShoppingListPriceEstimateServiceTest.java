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

    private static ShoppingListItem item(String name, String quantity, String unit) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .quantity(new BigDecimal(quantity))
                .unit(unit)
                .build();
    }
}
