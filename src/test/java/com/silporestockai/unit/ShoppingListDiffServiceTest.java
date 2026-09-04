package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.ShoppingListDelta;
import com.silporestockai.service.ShoppingListDiffService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShoppingListDiffServiceTest {

    private final ShoppingListDiffService service = new ShoppingListDiffService();

    private static ShoppingListItem item(String name, double quantity, String unit) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .quantity(BigDecimal.valueOf(quantity))
                .unit(unit)
                .build();
    }

    @Test
    void newLinesAreAdded() {
        ShoppingListDelta delta = service.diff(List.of(), List.of(item("Молоко", 1, "л")));

        assertThat(delta.added()).extracting(ShoppingListDelta.Line::name).containsExactly("Молоко");
        assertThat(delta.removed()).isEmpty();
        assertThat(delta.quantityChanged()).isEmpty();
    }

    @Test
    void missingLinesAreRemoved() {
        ShoppingListDelta delta = service.diff(List.of(item("Молоко", 1, "л")), List.of());

        assertThat(delta.removed()).extracting(ShoppingListDelta.Line::name).containsExactly("Молоко");
        assertThat(delta.added()).isEmpty();
    }

    @Test
    void differentQuantityIsAQuantityChangeNotAnAddAndRemove() {
        ShoppingListDelta delta = service.diff(List.of(item("Молоко", 1, "л")), List.of(item("Молоко", 2, "л")));

        assertThat(delta.quantityChanged())
                .extracting(ShoppingListDelta.QuantityChange::name)
                .containsExactly("Молоко");
        assertThat(delta.added()).isEmpty();
        assertThat(delta.removed()).isEmpty();
    }

    @Test
    void matchingIsCaseAndWhitespaceInsensitive() {
        ShoppingListDelta delta = service.diff(List.of(item("  Молоко ", 1, "л")), List.of(item("молоко", 1, "л")));

        assertThat(delta.unchangedCount()).isEqualTo(1);
        assertThat(delta.totalChanges()).isZero();
    }

    @Test
    void sameLineIsUnchangedAndNotRendered() {
        ShoppingListDelta delta = service.diff(List.of(item("Молоко", 1, "л")), List.of(item("Молоко", 1, "л")));

        assertThat(delta.unchangedCount()).isEqualTo(1);
        assertThat(delta.added()).isEmpty();
        assertThat(delta.removed()).isEmpty();
        assertThat(delta.quantityChanged()).isEmpty();
    }

    @Test
    void oneOrTwoChangesIsTrivial() {
        ShoppingListDelta oneChange = service.diff(List.of(), List.of(item("Молоко", 1, "л")));
        ShoppingListDelta twoChanges = service.diff(List.of(), List.of(item("Молоко", 1, "л"), item("Хліб", 1, "шт")));
        ShoppingListDelta threeChanges =
                service.diff(List.of(), List.of(item("Молоко", 1, "л"), item("Хліб", 1, "шт"), item("Сир", 1, "шт")));

        assertThat(oneChange.isTrivial()).isTrue();
        assertThat(twoChanges.isTrivial()).isTrue();
        assertThat(threeChanges.isTrivial()).isFalse();
    }
}
