package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.utils.GroupOrderScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("a group reply that asks for something to eat, not something to drink")
class GroupOrderScopeTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "чіпси й пиво",
                "візьми шашлик",
                "мені щось поїсти",
                "закуски які-небудь",
                "піца",
                "торт на день народження",
                "сир і ковбаса до вина",
                "давайте м'ясо на мангал"
            })
    void thisIsAskingForFood(String reply) {
        assertThat(GroupOrderScope.asksForFood(reply)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "пиво світле",
                "червоне вино сухе",
                "не п'ю — сік",
                "сьогодні за кермом, вода",
                ".",
                "",
                "віскі, якщо є"
            })
    void thisIsAskingForADrink(String reply) {
        assertThat(GroupOrderScope.asksForFood(reply)).isFalse();
    }

    @Test
    void nothingAtAllIsNotAFoodRequest() {
        assertThat(GroupOrderScope.asksForFood(null)).isFalse();
    }
}
