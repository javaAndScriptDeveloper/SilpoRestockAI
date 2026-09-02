package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.support.StubOAuthServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    @Autowired
    private UserRepository userRepository;

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
        // mcp_oauth_token.user_id is a foreign key to users since task 05, so the owner has to exist first.
        UUID userId = persistedUser();
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

    /**
     * Full Feign logging is on for every client — {@code FeignConfig} turns it on globally, so that a shape mismatch
     * like task 09's cart-id bug can be read straight from the log next time. That means this exchange's headers and
     * bodies are printed verbatim by Feign's own logger, which is exactly where {@code TokenResponse.toString()}'s
     * protection does not reach: this test proves the redacting logger catches what that override cannot.
     */
    @Test
    void fullFeignLoggingNeverPrintsTheExchangedTokensOrTheAuthorizationCode() throws Exception {
        UUID userId = persistedUser();
        String state = queryOf(mockMvc.perform(get("/auth/silpo/start").param("userId", userId.toString()))
                        .andReturn()
                        .getResponse()
                        .getHeader("Location"))
                .get("state");

        Logger logger = (Logger) LoggerFactory.getLogger("com.silporestockai.client.FeignHttp");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(
                    get("/auth/silpo/callback").param("code", "auth-code-123").param("state", state));
        } finally {
            logger.detachAppender(appender);
        }

        List<String> lines =
                appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        // Something was actually logged — otherwise this test would pass by accident, proving nothing.
        assertThat(lines).isNotEmpty();
        assertThat(lines)
                .noneMatch(line -> line.contains(StubOAuthServer.ACCESS_TOKEN))
                .noneMatch(line -> line.contains(StubOAuthServer.REFRESH_TOKEN))
                .noneMatch(line -> line.contains("auth-code-123"));
        assertThat(lines).anyMatch(line -> line.contains("***"));
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

    private UUID persistedUser() {
        return userRepository
                .save(User.builder()
                        .id(UUID.randomUUID())
                        .telegramChatId(System.nanoTime())
                        .createdAt(java.time.Instant.now())
                        .build())
                .getId();
    }
}
