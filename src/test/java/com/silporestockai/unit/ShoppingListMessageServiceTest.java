package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.PriceEstimate;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.service.telegram.ShoppingListMessageService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShoppingListMessageServiceTest {

    private final ShoppingListMessageService service = new ShoppingListMessageService();

    @Test
    void groupsItemsByCategoryInEncounterOrder() {
        List<ShoppingListItem> items = List.of(
                item("Молоко", "Молочні продукти"), item("Цибуля", "Овочі і фрукти"), item("Сир", "Молочні продукти"));

        var grouped = service.categorized(items);

        assertThat(grouped.keySet()).containsExactly("Молочні продукти", "Овочі і фрукти");
        assertThat(grouped.get("Молочні продукти"))
                .extracting(ShoppingListItem::getName)
                .containsExactly("Молоко", "Сир");
    }

    @Test
    void uncategorizedItemsFallUnderInshe() {
        List<ShoppingListItem> items = List.of(item("Щось", null));

        var grouped = service.categorized(items);

        assertThat(grouped.keySet()).containsExactly("Інше");
    }

    @Test
    void itemButtonsOffersOnlyPlusAndMinusNoDeleteButton() {
        ShoppingListItem item = item("Молоко", "Молочні продукти");

        List<TelegramButton> buttons = service.itemButtons(item);

        assertThat(buttons).extracting(TelegramButton::label).containsExactly("−", "+");
    }

    @Test
    void listTextCarriesTheEstimateWhenThereIsOne() {
        List<ShoppingListItem> items = List.of(item("Молоко", "Молочні продукти"), item("Сир", "Молочні продукти"));

        String full = service.listText(items, new PriceEstimate(new BigDecimal("1234.5"), 2, 0));
        String partial = service.listText(items, new PriceEstimate(new BigDecimal("800"), 5, 7));
        String none = service.listText(items, PriceEstimate.none());

        assertThat(full).contains("Всього 2 позиції.\nОрієнтовно ~1234.50 грн — точну суму покажу в кошику.");
        assertThat(partial).contains("Орієнтовно ~800.00 грн за 5 з 12 позицій — точну суму покажу в кошику.");
        assertThat(none).doesNotContain("Орієнтовно").contains("Всього 2 позиції.\nЯкщо все влаштовує");
    }

    private static ShoppingListItem item(String name, String category) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .quantity(BigDecimal.ONE)
                .unit("шт")
                .category(category)
                .build();
    }
}
