package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A user's Google OAuth tokens, one row per user.
 *
 * <p>Deliberately a separate table from {@code mcp_oauth_token}: different provider, different secrets, and that
 * table keys on {@code user_id}. Same protection though — AES-GCM ciphertext in both token columns, and a
 * {@code toString} that cannot print either.
 */
@Entity
@Table(name = "google_oauth_token")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class GoogleOAuthToken {

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
    public boolean isExpired(Instant now, Duration skew) {
        return expiresAt == null || !now.plus(skew).isBefore(expiresAt);
    }
}
