package com.silporestockai.client.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * The Silpo authorization server's OAuth 2.1 endpoints — Dynamic Client Registration (RFC 7591) and the token
 * endpoint. Follows the repository's Feign convention; only the MCP transport itself needs a different client.
 *
 * <p>No fallback is declared on purpose: a silently-degraded auth response would be worse than a failure.
 */
@FeignClient(name = "silpoOauth", url = "${silpo.mcp.issuer}")
public interface SilpoOAuthApiClient {

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    ClientRegistrationResponse register(@RequestBody ClientRegistrationRequest request);

    /**
     * Exchanges an authorization code, or redeems a refresh token. Form-encoded per RFC 6749.
     *
     * <p>The response carries secrets — never log the returned object.
     */
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    TokenResponse token(@RequestBody MultiValueMap<String, String> form);

    /**
     * Dynamic Client Registration request. The Silpo AS advertises {@code token_endpoint_auth_method: none}, so we
     * register as a public client and never hold a client secret.
     */
    record ClientRegistrationRequest(
            @JsonProperty("client_name") String clientName,
            @JsonProperty("redirect_uris") List<String> redirectUris,
            @JsonProperty("grant_types") List<String> grantTypes,
            @JsonProperty("response_types") List<String> responseTypes,
            @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod) {

        public static ClientRegistrationRequest publicClient(String clientName, String redirectUri) {
            return new ClientRegistrationRequest(
                    clientName,
                    List.of(redirectUri),
                    List.of("authorization_code", "refresh_token"),
                    List.of("code"),
                    "none");
        }
    }

    /** Registration response. A public client gets no {@code client_secret}, so none is modelled. */
    record ClientRegistrationResponse(
            @JsonProperty("client_id") String clientId) {}

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
