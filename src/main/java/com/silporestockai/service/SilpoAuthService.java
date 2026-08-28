package com.silporestockai.service;

import com.silporestockai.client.mcp.SilpoAccessTokenProvider;
import com.silporestockai.client.mcp.SilpoOAuthApiClient;
import com.silporestockai.config.SilpoMcpProperties;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.exception.ApplicationException;
import com.silporestockai.exception.SilpoNotConnectedException;
import com.silporestockai.model.SilpoLoginState;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.utils.TokenCipher;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Owns the Silpo OAuth 2.1 lifecycle: Dynamic Client Registration, the PKCE authorization-code flow, refresh, and
 * encrypted server-side storage.
 *
 * <p>Tokens never leave this class in plaintext except through {@link #accessToken(UUID)}, which the MCP transport
 * uses to build an {@code Authorization} header. Nothing here logs a token, a refresh token or a PKCE verifier — the
 * documentation's explicit requirement is that the token stays server-side.
 *
 * <p>Pending logins are held in memory with a short TTL rather than in a table. That is a deliberate single-instance
 * assumption: the window is minutes long and a lost login simply means the guest clicks the link again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SilpoAuthService implements SilpoAccessTokenProvider {

    /** Refresh this far ahead of the real expiry so a call never races the deadline. */
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);

    private final SilpoMcpProperties properties;
    private final SilpoOAuthApiClient oauthApiClient;
    private final SilpoOAuthTokenRepository tokenRepository;
    private final TokenCipher tokenCipher;

    private final Map<String, SilpoLoginState> pendingLogins = new ConcurrentHashMap<>();
    private final AtomicReference<String> registeredClientId = new AtomicReference<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * Starts a login: generates PKCE material, remembers it against an opaque state value, and returns the URL the
     * guest must open. The verifier stays here; only the S256 challenge travels to Silpo.
     */
    public String buildAuthorizationUrl(UUID userId) {
        String codeVerifier = randomUrlSafe(64);
        String state = randomUrlSafe(24);
        pendingLogins.put(state, new SilpoLoginState(userId, codeVerifier, Instant.now()));
        purgeExpiredLogins();

        String url = properties.authorizationEndpoint() + "?response_type=code"
                + "&client_id=" + encode(clientId())
                + "&redirect_uri=" + encode(properties.redirectUri())
                + "&code_challenge=" + encode(challengeFor(codeVerifier))
                + "&code_challenge_method=S256"
                + "&state=" + encode(state)
                // RFC 8707: the resource identifier is the MCP endpoint itself, per
                // /.well-known/oauth-protected-resource.
                + "&resource=" + encode(properties.resource());
        log.info("built a Silpo authorization URL for user {}", userId);
        return url;
    }

    /**
     * Completes a login by exchanging the authorization code. Returns the user the code belonged to.
     *
     * @throws ApplicationException if the state is unknown or expired — which is also how a forged callback is rejected
     */
    @Transactional
    public UUID completeLogin(String code, String state) {
        SilpoLoginState login = pendingLogins.remove(state);
        if (login == null) {
            throw new ApplicationException(HttpStatus.BAD_REQUEST, "unknown or already used login state");
        }
        if (isExpired(login)) {
            throw new ApplicationException(HttpStatus.BAD_REQUEST, "this login link has expired, please start again");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());
        form.add("client_id", clientId());
        form.add("code_verifier", login.codeVerifier());
        form.add("resource", properties.resource());

        SilpoOAuthApiClient.TokenResponse response = oauthApiClient.token(form);
        store(login.userId(), response);
        log.info("user {} connected their Silpo account", login.userId());
        return login.userId();
    }

    @Override
    @Transactional
    public String accessToken(UUID userId) {
        SilpoOAuthToken token =
                tokenRepository.findByUserId(userId).orElseThrow(() -> new SilpoNotConnectedException(userId));
        if (token.isExpired(Instant.now(), EXPIRY_SKEW)) {
            if (!refresh(userId)) {
                throw new SilpoNotConnectedException(userId);
            }
            token = tokenRepository.findByUserId(userId).orElseThrow(() -> new SilpoNotConnectedException(userId));
        }
        return tokenCipher.decrypt(token.getAccessToken());
    }

    @Override
    @Transactional
    public boolean refresh(UUID userId) {
        SilpoOAuthToken token = tokenRepository.findByUserId(userId).orElse(null);
        if (token == null || token.getRefreshToken() == null) {
            log.warn("cannot refresh the Silpo token for user {}: no refresh token stored", userId);
            return false;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", tokenCipher.decrypt(token.getRefreshToken()));
        form.add("client_id", clientId());
        form.add("resource", properties.resource());

        try {
            store(userId, oauthApiClient.token(form));
            log.info("refreshed the Silpo token for user {}", userId);
            return true;
        } catch (RuntimeException e) {
            // Message only: the exception may carry the request body, which contains the refresh token.
            log.warn("failed to refresh the Silpo token for user {}", userId);
            return false;
        }
    }

    /** True when the user has completed the OAuth login at least once. */
    public boolean isConnected(UUID userId) {
        return tokenRepository.findByUserId(userId).isPresent();
    }

    private void store(UUID userId, SilpoOAuthApiClient.TokenResponse response) {
        if (response == null || response.accessToken() == null) {
            throw new ApplicationException(HttpStatus.BAD_GATEWAY, "Silpo returned no access token");
        }
        Instant now = Instant.now();
        SilpoOAuthToken token = tokenRepository
                .findByUserId(userId)
                .orElseGet(() ->
                        SilpoOAuthToken.builder().userId(userId).createdAt(now).build());

        token.setAccessToken(tokenCipher.encrypt(response.accessToken()));
        if (response.refreshToken() != null) {
            token.setRefreshToken(tokenCipher.encrypt(response.refreshToken()));
        }
        token.setExpiresAt(response.expiresIn() == null ? null : now.plusSeconds(response.expiresIn()));
        token.setUpdatedAt(now);
        tokenRepository.save(token);
    }

    /**
     * The registered client id. Configured values win; otherwise the client registers itself once via RFC 7591
     * Dynamic Client Registration. Silpo issues public clients, so there is no secret to protect here — the id is
     * logged so it can be pinned in configuration afterwards.
     */
    private String clientId() {
        if (properties.clientId() != null && !properties.clientId().isBlank()) {
            return properties.clientId();
        }
        return registeredClientId.updateAndGet(existing -> {
            if (existing != null) {
                return existing;
            }
            var request = SilpoOAuthApiClient.ClientRegistrationRequest.publicClient(
                    properties.clientName(), properties.redirectUri());
            String clientId = oauthApiClient.register(request).clientId();
            log.info(
                    "registered a Silpo OAuth client dynamically: client_id={} — pin it via SILPO_MCP_CLIENT_ID",
                    clientId);
            return clientId;
        });
    }

    private boolean isExpired(SilpoLoginState login) {
        return login.createdAt().plus(properties.loginStateTtl()).isBefore(Instant.now());
    }

    private void purgeExpiredLogins() {
        pendingLogins.values().removeIf(this::isExpired);
    }

    private String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private String challengeFor(String codeVerifier) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
