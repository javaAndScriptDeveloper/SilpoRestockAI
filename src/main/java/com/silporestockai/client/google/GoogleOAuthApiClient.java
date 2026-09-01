package com.silporestockai.client.google;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Google's OAuth token endpoint.
 *
 * <p>Feign, like {@code SilpoOAuthApiClient} and for the same reason: a form-encoded POST returning JSON is exactly
 * what the repository's outbound-HTTP convention is for. Google's own Java client library would bring a second HTTP
 * transport and credential store to do this one call.
 *
 * <p>No fallback: a silently-degraded auth response would be worse than a failure.
 */
@FeignClient(name = "googleOauth", url = "${google.calendar.token-endpoint}")
public interface GoogleOAuthApiClient {

    /** Exchanges an authorization code, or redeems a refresh token. The response carries secrets — never log it. */
    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    TokenResponse token(@RequestBody MultiValueMap<String, String> form);

    /** Token endpoint response. {@code toString} is overridden so the tokens cannot reach a log line. */
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn) {

        @Override
        public String toString() {
            return "TokenResponse[tokenType=%s, expiresIn=%s, refreshTokenPresent=%s]"
                    .formatted(tokenType, expiresIn, refreshToken != null);
        }
    }
}
