package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.utils.OAuthCallbackPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the OAuth landing page is branded, escaped and the same shape for both providers")
class OAuthCallbackPageTest {

    @Test
    void successCarriesTheBrandColourAndTheGivenCopy() {
        String html = OAuthCallbackPage.success("Календар підключено", "Можна повертатися в Telegram.");

        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("#FF8200");
        assertThat(html).contains("Календар підключено").contains("Можна повертатися в Telegram.");
        assertThat(html).doesNotContain("{{");
    }

    @Test
    void failureUsesANeutralAccentSoItCannotBeMistakenForSuccess() {
        String success = OAuthCallbackPage.success("Готово", "Все добре.");
        String failure = OAuthCallbackPage.failure("Не вдалось", "Спробуй ще раз.");

        assertThat(failure).contains("Не вдалось").contains("Спробуй ще раз.");
        assertThat(failure).doesNotContain("{{");
        // The two states must be visually distinguishable, not the same page with different words.
        assertThat(failure).isNotEqualTo(success);
        assertThat(failure).contains("#2E2E2E");
    }

    /**
     * The title and message are ours today, but a page that interpolates raw HTML is one refactor away from
     * reflecting a query parameter into the document.
     */
    @Test
    void copyIsHtmlEscaped() {
        String html = OAuthCallbackPage.success("<script>alert(1)</script>", "a & b");

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;").contains("a &amp; b");
    }
}
