package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.support.StubOAuthServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Covers the browser half of the OAuth flow end to end against a stub authorization server: the PKCE challenge that
 * leaves the process, the code exchange, and what actually lands in the database.
 */
class SilpoOAuthIntegrationTest extends AbstractIntegrationTest {

    private static final StubOAuthServer STUB = startStub();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    @DynamicPropertySource
    static void oauthIssuer(DynamicPropertyRegistry registry) {
        registry.add("silpo.mcp.issuer", STUB::issuer);
    }

    private static StubOAuthServer startStub() {
        try {
            return new StubOAuthServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void startRedirectsToSilpoWithAnS256ChallengeAndNeverLeaksTheVerifier() throws Exception {
        UUID userId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(get("/auth/silpo/start").param("userId", userId.toString()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        Map<String, String> query = queryOf(result.getResponse().getHeader("Location"));
        assertThat(query)
                .containsEntry("response_type", "code")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri", "http://localhost:8080/auth/silpo/callback")
                .containsEntry("resource", "https://mcp.silpo.ua/mcp")
                .containsKeys("client_id", "code_challenge", "state");
        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void callbackExchangesTheCodeAndStoresBothTokensEncrypted() throws Exception {
        UUID userId = UUID.randomUUID();
        String state = queryOf(mockMvc.perform(get("/auth/silpo/start").param("userId", userId.toString()))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location"))
                .get("state");

        MvcResult callback = mockMvc.perform(get("/auth/silpo/callback")
                        .param("code", "auth-code-123")
                        .param("state", state))
                .andReturn();

        assertThat(callback.getResponse().getStatus()).isEqualTo(200);
        assertThat(callback.getResponse().getContentAsString())
                .doesNotContain(StubOAuthServer.ACCESS_TOKEN)
                .doesNotContain(StubOAuthServer.REFRESH_TOKEN);

        assertThat(STUB.lastTokenForm())
                .containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "auth-code-123")
                .containsEntry("resource", "https://mcp.silpo.ua/mcp")
                .containsKeys("code_verifier", "client_id", "redirect_uri");

        SilpoOAuthToken stored = tokenRepository.findByUserId(userId).orElseThrow();
        assertThat(stored.getAccessToken()).isNotEqualTo(StubOAuthServer.ACCESS_TOKEN);
        assertThat(stored.getRefreshToken()).isNotEqualTo(StubOAuthServer.REFRESH_TOKEN);
        assertThat(tokenCipher.decrypt(stored.getAccessToken())).isEqualTo(StubOAuthServer.ACCESS_TOKEN);
        assertThat(tokenCipher.decrypt(stored.getRefreshToken())).isEqualTo(StubOAuthServer.REFRESH_TOKEN);
        assertThat(stored.getExpiresAt()).isNotNull();
        assertThat(stored.toString()).doesNotContain(StubOAuthServer.ACCESS_TOKEN);
    }

    @Test
    void callbackRejectsAnUnknownState() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/auth/silpo/callback").param("code", "whatever").param("state", "forged-state"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    private static Map<String, String> queryOf(String location) {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = URI.create(location).getRawQuery();
        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            query.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return query;
    }
}
