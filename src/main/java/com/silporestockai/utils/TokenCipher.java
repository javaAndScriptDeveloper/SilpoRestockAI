package com.silporestockai.utils;

import com.silporestockai.config.SilpoMcpProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for OAuth tokens at rest. A fresh random IV is generated per encryption and prefixed to
 * the ciphertext, so encrypting the same token twice never yields the same string.
 *
 * <p>The key comes from {@code silpo.mcp.token-encryption-key} (base64, 32 bytes). When it is unset an ephemeral
 * key is generated at startup: no key is ever committed to the repository, and local runs and tests still work —
 * at the cost of stored tokens becoming unreadable after a restart, which is logged loudly.
 *
 * <p>Nothing here logs plaintext or ciphertext.
 */
@Slf4j
@Component
public class TokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BITS = 256;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(SilpoMcpProperties properties) {
        this.key = resolveKey(properties.tokenEncryptionKey());
    }

    /** Encrypts a token, returning base64 of {@code iv || ciphertext}. */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to encrypt token", e);
        }
    }

    /** Reverses {@link #encrypt(String)}. */
    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("ciphertext too short to contain an IV");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, IV_LENGTH_BYTES, combined.length - IV_LENGTH_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to decrypt token", e);
        }
    }

    private SecretKey resolveKey(String configured) {
        if (configured != null && !configured.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(configured.trim());
            if (raw.length != KEY_LENGTH_BITS / 8) {
                throw new IllegalStateException("silpo.mcp.token-encryption-key must decode to %d bytes, got %d"
                        .formatted(KEY_LENGTH_BITS / 8, raw.length));
            }
            return new SecretKeySpec(raw, "AES");
        }
        log.warn("silpo.mcp.token-encryption-key is not set — generating an ephemeral AES key. "
                + "Stored Silpo tokens will be unreadable after a restart and every user must reconnect.");
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(KEY_LENGTH_BITS);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to generate an ephemeral token encryption key", e);
        }
    }
}
