package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A guest's Silpo MCP OAuth tokens, one row per user.
 *
 * <p>Both token columns hold AES-GCM ciphertext produced by {@code TokenCipher} — never plaintext. {@code toString}
 * deliberately omits them so an accidental log statement cannot leak a token.
 *
 * <p>{@code userId} is a plain unique column rather than a foreign key: the {@code users} table belongs to task 05,
 * which adds the constraint once it exists.
 */
@Entity
@Table(name = "mcp_oauth_token")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class SilpoOAuthToken {

    @Id
    @Column(name = "user_id", nullable = false)
    @ToString.Include
    private UUID userId;

    @Column(name = "access_token", nullable = false, length = 4096)
    private String accessToken;

    @Column(name = "refresh_token", length = 4096)
    private String refreshToken;

    @Column(name = "expires_at")
    @ToString.Include
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    @ToString.Include
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @ToString.Include
    private Instant updatedAt;

    /** True when the access token is absent or within {@code skew} of expiry. */
    public boolean isExpired(Instant now, java.time.Duration skew) {
        return expiresAt != null && !now.plus(skew).isBefore(expiresAt);
    }
}
