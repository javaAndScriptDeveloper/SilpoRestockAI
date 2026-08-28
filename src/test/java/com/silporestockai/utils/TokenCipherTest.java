package com.silporestockai.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.silporestockai.config.SilpoMcpProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private static final String TOKEN = "silpo-access-token-value";

    @Test
    void roundTripsATokenWithAConfiguredKey() {
        TokenCipher cipher = cipherWith(randomKey());

        String encrypted = cipher.encrypt(TOKEN);

        assertThat(encrypted).isNotEqualTo(TOKEN).doesNotContain(TOKEN);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(TOKEN);
    }

    @Test
    void usesAFreshIvSoTheSameTokenNeverEncryptsToTheSameCiphertext() {
        TokenCipher cipher = cipherWith(randomKey());

        assertThat(cipher.encrypt(TOKEN)).isNotEqualTo(cipher.encrypt(TOKEN));
    }

    @Test
    void fallsBackToAnEphemeralKeyWhenNoneIsConfigured() {
        TokenCipher cipher = cipherWith("");

        assertThat(cipher.decrypt(cipher.encrypt(TOKEN))).isEqualTo(TOKEN);
    }

    @Test
    void rejectsAKeyOfTheWrongLength() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> cipherWith(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void refusesCiphertextThatCannotHoldAnIv() {
        TokenCipher cipher = cipherWith(randomKey());
        String tooShort = Base64.getEncoder().encodeToString(new byte[4]);

        assertThatThrownBy(() -> cipher.decrypt(tooShort)).isInstanceOf(IllegalArgumentException.class);
    }

    private static TokenCipher cipherWith(String key) {
        return new TokenCipher(new SilpoMcpProperties(
                "https://mcp.silpo.ua/mcp",
                "https://mcp.silpo.ua",
                "https://mcp.silpo.ua/mcp",
                "client",
                "Komora",
                "http://localhost:8080/auth/silpo/callback",
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                key));
    }

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }
}
