package com.silporestockai.service;

import com.silporestockai.client.google.GoogleOAuthApiClient;
import com.silporestockai.config.GoogleCalendarProperties;
import com.silporestockai.entity.GoogleOAuthToken;
import com.silporestockai.exception.ApplicationException;
import com.silporestockai.model.GoogleLoginState;
import com.silporestockai.repository.GoogleOAuthTokenRepository;
import com.silporestockai.utils.TokenCipher;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The Google OAuth half of the calendar integration: consent URL, code exchange, refresh, encrypted storage.
 *
 * <p>Follows {@code SilpoAuthService} deliberately closely — same in-memory pending-login map with a short TTL, same
 * {@link TokenCipher}, same rule that a token never leaves this class except as a bearer header. What differs is the
 * provider and the table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    /** Refresh this far ahead of the real expiry so a call never races the deadline. */
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);

    /** A consent screen somebody opened and abandoned is worthless after this. */
    private static final Duration LOGIN_TTL = Duration.ofMinutes(15);

    private final GoogleCalendarProperties properties;
    private final GoogleOAuthApiClient oauthApiClient;
    private final GoogleOAuthTokenRepository tokenRepository;
    private final TokenCipher tokenCipher;
    private final Clock clock;

    private final Map<String, GoogleLoginState> pendingLogins = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** Whether the integration exists at all in this deployment. */
    public boolean configured() {
        return properties.configured();
    }

    /** Whether this particular user ever connected a calendar. */
    public boolean isConnected(UUID userId) {
        return tokenRepository.findById(userId).isPresent();
    }

    /** Starts a login. The opaque state is the only thing that travels through the browser. */
    public String buildAuthorizationUrl(UUID userId) {
        requireConfigured();
        String state = randomUrlSafe(24);
        pendingLogins.put(state, new GoogleLoginState(userId, clock.instant()));
        purgeExpiredLogins();

        String url = properties.authorizationEndpoint() + "?response_type=code"
                + "&client_id=" + encode(properties.clientId())
                + "&redirect_uri=" + encode(properties.redirectUri())
                + "&scope=" + encode(properties.scope())
                // Offline plus consent is what makes Google hand over a refresh token at all.
                + "&access_type=offline&prompt=consent"
                + "&state=" + encode(state);
        log.info("built a Google authorization URL for user {}", userId);
        return url;
    }

    /** Completes a login and stores the tokens encrypted. Returns whose login it was. */
    @Transactional
    public UUID completeLogin(String code, String state) {
        GoogleLoginState pending = pendingLogins.remove(state);
        if (pending == null || pending.startedAt().plus(LOGIN_TTL).isBefore(clock.instant())) {
            throw new ApplicationException(HttpStatus.BAD_REQUEST, "unknown or expired Google login state");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());

        GoogleOAuthApiClient.TokenResponse response = oauthApiClient.token(form);
        store(pending.userId(), response);
        log.info("connected a Google calendar for user {}", pending.userId());
        return pending.userId();
    }

    /**
     * A usable access token, refreshed when it is about to expire.
     *
     * <p>Empty rather than an exception when the user never connected: "no calendar" is the common case, and the
     * caller treats it as nothing to do.
     */
    @Transactional
    public java.util.Optional<String> accessToken(UUID userId) {
        return tokenRepository.findById(userId).map(token -> {
            if (!token.isExpired(clock.instant(), EXPIRY_SKEW)) {
                return tokenCipher.decrypt(token.getAccessToken());
            }
            if (token.getRefreshToken() == null) {
                log.warn("Google access token for user {} expired and there is no refresh token", userId);
                return null;
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", tokenCipher.decrypt(token.getRefreshToken()));
            form.add("client_id", properties.clientId());
            form.add("client_secret", properties.clientSecret());

            GoogleOAuthApiClient.TokenResponse refreshed = oauthApiClient.token(form);
            store(userId, refreshed);
            return refreshed.accessToken();
        });
    }

    private void store(UUID userId, GoogleOAuthApiClient.TokenResponse response) {
        Instant now = clock.instant();
        GoogleOAuthToken token = tokenRepository
                .findById(userId)
                .orElseGet(() ->
                        GoogleOAuthToken.builder().userId(userId).createdAt(now).build());
        token.setAccessToken(tokenCipher.encrypt(response.accessToken()));
        // A refresh grant answers without a refresh token; the one already stored stays valid.
        if (response.refreshToken() != null) {
            token.setRefreshToken(tokenCipher.encrypt(response.refreshToken()));
        }
        token.setExpiresAt(response.expiresIn() == null ? null : now.plusSeconds(response.expiresIn()));
        token.setUpdatedAt(now);
        tokenRepository.save(token);
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new ApplicationException(
                    HttpStatus.SERVICE_UNAVAILABLE, "the Google Calendar integration is not configured");
        }
    }

    private void purgeExpiredLogins() {
        Instant cutoff = clock.instant().minus(LOGIN_TTL);
        pendingLogins.entrySet().removeIf(entry -> entry.getValue().startedAt().isBefore(cutoff));
    }

    private String randomUrlSafe(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
