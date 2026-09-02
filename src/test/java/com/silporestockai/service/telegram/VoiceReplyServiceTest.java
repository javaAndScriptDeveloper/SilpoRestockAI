package com.silporestockai.service.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("a message already speakable as written skips the style rewrite")
class VoiceReplyServiceTest {

    @Test
    void aPlainShortConfirmationNeedsNoRewrite() {
        assertThat(VoiceReplyService.needsStyleRewrite("Записав.")).isFalse();
        assertThat(VoiceReplyService.needsStyleRewrite("Скасував. Скажи, що змінити, і зберу кошик заново."))
                .isFalse();
    }

    @Test
    void aDigitForcesTheRewrite() {
        assertThat(VoiceReplyService.needsStyleRewrite("Разом: 73.50 грн")).isTrue();
    }

    @Test
    void aLinkForcesTheRewrite() {
        assertThat(VoiceReplyService.needsStyleRewrite("Оформити: https://silpo.ua/checkout/cart-1"))
                .isTrue();
    }

    @Test
    void aSecondLineForcesTheRewrite() {
        assertThat(VoiceReplyService.needsStyleRewrite("Ось що пропоную взяти:\n— Молоко"))
                .isTrue();
    }

    @Test
    void anEmDashAloneIsOrdinaryPunctuationNotAListMarker() {
        // Most short confirmations in this app use one; it must not force a rewrite by itself.
        assertThat(VoiceReplyService.needsStyleRewrite("Вимкнув голосові відповіді — до зустрічі."))
                .isFalse();
    }

    @Test
    void anythingLongIsAssumedToHaveRealStructureRegardlessOfContent() {
        String longPlainText = "а".repeat(121);

        assertThat(VoiceReplyService.needsStyleRewrite(longPlainText)).isTrue();
    }
}
