package com.silporestockai.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("secrets never survive into a log line, whatever shape they arrived in")
class SecretRedactorTest {

    @Test
    void redactsABearerHeader() {
        String line = SecretRedactor.redact("Authorization: Bearer sk-ant-real-secret-value");

        assertThat(line).isEqualTo("Authorization: Bearer ***").doesNotContain("sk-ant-real-secret-value");
    }

    @Test
    void redactsTheRespeecherAndTelegramHeaders() {
        assertThat(SecretRedactor.redact("X-API-Key: stub-respeecher-key"))
                .isEqualTo("X-API-Key: ***")
                .doesNotContain("stub-respeecher-key");
        assertThat(SecretRedactor.redact("X-Telegram-Bot-Api-Secret-Token: efd38d42abcd"))
                .isEqualTo("X-Telegram-Bot-Api-Secret-Token: ***")
                .doesNotContain("efd38d42abcd");
    }

    /**
     * The inbound request filter joins every header into one line — {@code "name: value; "} — rather than one line
     * per header the way Feign's own logger does. A header rule anchored to the start of a line, as this one first
     * was, never matches a header that is not first in that joined string.
     */
    @Test
    void redactsASecretHeaderInsideAJoinedSingleLineHeaderDump() {
        String line = "Content-Type: application/json; X-Telegram-Bot-Api-Secret-Token: stub-webhook-secret; "
                + "Content-Length: 165; ";

        String redacted = SecretRedactor.redact(line);

        assertThat(redacted)
                .doesNotContain("stub-webhook-secret")
                .contains("Content-Type: application/json")
                .contains("Content-Length: 165")
                .contains("X-Telegram-Bot-Api-Secret-Token: ***");
    }

    @Test
    void redactsOAuthFieldsInAJsonBody() {
        String body = """
                {"access_token":"real-access-token","refresh_token":"real-refresh-token","token_type":"Bearer"}""";

        String redacted = SecretRedactor.redact(body);

        assertThat(redacted)
                .doesNotContain("real-access-token")
                .doesNotContain("real-refresh-token")
                .contains("\"access_token\":\"***\"")
                .contains("\"refresh_token\":\"***\"")
                // What isn't a secret stays exactly as it was — this must read like a fixed body, not a wall of stars.
                .contains("\"token_type\":\"Bearer\"");
    }

    @Test
    void redactsOAuthFieldsInAFormEncodedBody() {
        String form = "grant_type=authorization_code&code=real-auth-code&client_secret=real-client-secret"
                + "&redirect_uri=http://localhost:8080/auth/google/callback";

        String redacted = SecretRedactor.redact(form);

        assertThat(redacted)
                .doesNotContain("real-auth-code")
                .doesNotContain("real-client-secret")
                .contains("grant_type=authorization_code")
                .contains("code=***")
                .contains("client_secret=***")
                .contains("redirect_uri=http://localhost:8080/auth/google/callback");
    }

    @Test
    void redactsAnAuthorizationCodeInACallbackUrl() {
        String url = "/auth/silpo/callback?code=real-auth-code&state=opaque-state-value";

        // The state value is not a secret — Silpo's PKCE verifier never leaves the server — so it survives.
        assertThat(SecretRedactor.redact(url))
                .doesNotContain("real-auth-code")
                .contains("code=***")
                .contains("state=opaque-state-value");
    }

    @Test
    void doesNotTouchTextWithNothingToRedact() {
        String text = "MCP -> silpo_get_time_slots {branchId=branch-7}";

        assertThat(SecretRedactor.redact(text)).isEqualTo(text);
    }

    @Test
    void handlesNullAndEmptyGracefully() {
        assertThat(SecretRedactor.redact(null)).isNull();
        assertThat(SecretRedactor.redact("")).isEmpty();
    }

    @Test
    void truncatesALongBodyButLeavesAShortOneAlone() {
        String longBody = "x".repeat(3000);

        assertThat(SecretRedactor.truncate(longBody, 2000)).hasSize(2000 + "…(truncated)".length());
        assertThat(SecretRedactor.truncate("short", 2000)).isEqualTo("short");
        assertThat(SecretRedactor.truncate(null, 2000)).isNull();
    }
}
