package com.silporestockai.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("category word matching (task 63)")
class CategoryWordsTest {

    @Test
    void matchesAWholeWordWhateverTheCase() {
        assertThat(CategoryWords.matches("Молоко", "молоко")).isTrue();
        assertThat(CategoryWords.matches("Молоко 2.5%", "молоко")).isTrue();
        assertThat(CategoryWords.matches("  чай зелений ", "Чай")).isTrue();
    }

    @Test
    void doesNotMatchAWordItIsMerelyInside() {
        assertThat(CategoryWords.matches("Молокопродукти", "молоко")).isFalse();
        assertThat(CategoryWords.matches("Сирники", "сир")).isFalse();
    }

    @Test
    void treatsNullAndBlankAsNoMatch() {
        assertThat(CategoryWords.matches(null, "молоко")).isFalse();
        assertThat(CategoryWords.matches("Молоко", null)).isFalse();
        assertThat(CategoryWords.matches("Молоко", "   ")).isFalse();
    }

    @Test
    void normalisesNullSafely() {
        assertThat(CategoryWords.normalise(null)).isEmpty();
        assertThat(CategoryWords.normalise("  Чай  ")).isEqualTo("чай");
    }
}
