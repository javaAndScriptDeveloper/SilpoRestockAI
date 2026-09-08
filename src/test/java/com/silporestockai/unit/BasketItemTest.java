package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.model.BasketItem;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BasketItemTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsABasketStoredBeforeTheRequestedNameExisted() throws Exception {
        // Every baseline_basket and customer_order row written before task 39's follow-up has five fields.
        BasketItem stored = MAPPER.readValue("""
                {"silpoProductId":"p-1","name":"Цибуля ріпчаста жовта","unit":"кг","quantity":1,"price":21.99}""", BasketItem.class);

        assertThat(stored.requestedName()).isNull();
        assertThat(stored.price()).isEqualByComparingTo("21.99");
    }

    @Test
    void keepsTheRequestedNameThroughAJsonRoundTrip() throws Exception {
        BasketItem line = new BasketItem(
                "p-1", "Цибуля ріпчаста жовта", "кг", new BigDecimal("1"), new BigDecimal("21.99"), "Цибуля");

        BasketItem back = MAPPER.readValue(MAPPER.writeValueAsString(line), BasketItem.class);

        assertThat(back.requestedName()).isEqualTo("Цибуля");
    }

    @Test
    void leavesTheRequestedNameNullWhenNobodyAskedForTheLine() {
        // The top-up and partner-placement paths add a product no list line asked for.
        BasketItem line = new BasketItem("p-1", "Цибуля ріпчаста жовта", "кг", new BigDecimal("1"), null);

        assertThat(line.requestedName()).isNull();
    }
}
