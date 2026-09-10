package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.GiftRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Caught live on 2026-09-10, not in a test: «надішли подарунок на Київ, вулиця Хрещатик 22, кв. 42, щось до
 * кави» named no phone, and the structured call answered {@code phone=".null"}. The string is not blank, so the
 * «no number — ask the sender» branch never ran, and {@code .null} was written onto the live cart as the number
 * a courier would dial.
 */
@DisplayName("the model's ways of writing «nothing»")
class GiftRequestCleaningTest {

    @Test
    void theDotNullSentinelIsNotAPhoneNumber() {
        GiftRequest cleaned =
                new GiftRequest(".null", "Київ, вулиця Хрещатик, 22", "42", ".null", "щось до кави").cleaned();

        assertThat(cleaned.phone()).isNull();
        assertThat(cleaned.recipientUsername()).isNull();
        assertThat(cleaned.address()).isEqualTo("Київ, вулиця Хрещатик, 22");
    }

    @Test
    void soIsAPlainNullWordOrAnEmptyString() {
        GiftRequest cleaned = new GiftRequest("null", "  ", null, "NULL", "  подарунок  ").cleaned();

        assertThat(cleaned.recipientUsername()).isNull();
        assertThat(cleaned.address()).isNull();
        assertThat(cleaned.flat()).isNull();
        assertThat(cleaned.phone()).isNull();
        assertThat(cleaned.theme()).isEqualTo("подарунок");
    }

    @Test
    void arealAnswerSurvivesUntouched() {
        GiftRequest cleaned = new GiftRequest("olena", null, "42", "+380671234567", "щось до кави").cleaned();

        assertThat(cleaned.recipientUsername()).isEqualTo("olena");
        assertThat(cleaned.phone()).isEqualTo("+380671234567");
        assertThat(cleaned.flat()).isEqualTo("42");
    }
}
