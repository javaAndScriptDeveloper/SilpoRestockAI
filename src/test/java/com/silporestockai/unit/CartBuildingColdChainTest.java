package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.service.CartBuildingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("a blackout cart is built from a shelf, not from a fridge")
class CartBuildingColdChainTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Сир Spomlek «Радамер» нарізка 150г",
                "Шинка Алан Куряча в/к в/ґ, нарізка",
                "Ковбаса Глобино Сервелат в/к",
                "Молоко Яготинське 2.6%",
                "Йогурт Активіа питний",
                "Сметана Президент 20%",
                "Масло вершкове Селянське",
                "Морозиво Ласунка пломбір",
                "Пельмені Геркулес з м'ясом",
                "Салат Олів'є ваговий, охолоджений",
                "Паштет Delikat з печінки, охолоджений",
                "Вареники з картоплею заморожені",
                "Сирок глазурований Волошкове поле"
            })
    void theseNeverEnterTheCandidatePool(String name) {
        assertThat(CartBuildingService.needsAFridge(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Консерви рибні Аквамарин Тунець у власному соку",
                "Паштет Onissi печінковий, консерва 100г",
                "Хліб «Київхліб» «Тост» злаковий нарізаний",
                "Вода питна «Природне джерело» негазована",
                "Сік Jaffa яблучний",
                "Печиво Roshen Марія вершкове",
                "Сухарики Флінт з беконом",
                // Dropped on a live run before the marker was anchored to the first word — «молоком» is in it.
                "Хліб «Київхліб» британський світлий з молоком нарізаний",
                "Сироп Monin карамель",
                "Масло соняшникове Олейна рафіноване"
            })
    void theseAreShelfStableAndStay(String name) {
        assertThat(CartBuildingService.needsAFridge(name)).isFalse();
    }
}
