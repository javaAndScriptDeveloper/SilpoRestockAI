package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.ShoppingListItem;
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
