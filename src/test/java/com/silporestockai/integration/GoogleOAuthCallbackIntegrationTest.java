package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.silporestockai.entity.User;
import com.silporestockai.repository.GoogleOAuthTokenRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubGoogleServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("finishing the Google consent screen is visible in the browser and in the chat")
class GoogleOAuthCallbackIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "601:stub-bot-token";
    private static final long CHAT_ID = 60101L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubGoogleServer GOOGLE = startGoogle();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GoogleOAuthTokenRepository googleTokenRepository;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubGoogleServer startGoogle() {
        try {
            return new StubGoogleServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Google stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("google.calendar.client-id", () -> "stub-google-client");
        registry.add("google.calendar.client-secret", () -> "stub-google-secret");
        registry.add("google.calendar.token-endpoint", GOOGLE::tokenEndpoint);
        registry.add("google.calendar.api-url", GOOGLE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        GOOGLE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        GOOGLE.reset();
        googleTokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userAccountService.findOrCreate(CHAT_ID);
    }

    @Test
    void aFinishedConsentRendersABrandedPageAndTellsTheChatWithoutBeingAsked() throws Exception {
        String state = startLogin();

        MvcResult callback = mockMvc.perform(
                        get("/auth/google/callback").param("code", "google-code").param("state", state))
                .andReturn();

        assertThat(callback.getResponse().getStatus()).isEqualTo(200);
        String html = callback.getResponse().getContentAsString();
        assertThat(html).contains("#FF8200").contains("Комора").contains("Календар підключено");
        assertThat(html).doesNotContain("stub-google-access").doesNotContain("google-code");

        assertThat(googleTokenRepository.findById(user.getId())).isPresent();

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
        assertThat(TELEGRAM.sentMessages().getFirst().path("chat_id").asLong()).isEqualTo(CHAT_ID);
        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText()).contains("Календар підключено");
    }

    /**
     * Declining the consent screen sends back {@code error=access_denied} and no {@code code} at all. Before this the
     * required {@code code} parameter turned that into a problem+json 500 in the user's face, and the chat heard
     * nothing.
     */
    @Test
    void aDeclinedConsentRendersABrandedErrorPageAndSaysSoInTheChat() throws Exception {
        String state = startLogin();

        MvcResult callback = mockMvc.perform(
                        get("/auth/google/callback").param("error", "access_denied").param("state", state))
                .andReturn();

        assertThat(callback.getResponse().getStatus()).isEqualTo(400);
        assertThat(callback.getResponse().getContentType()).startsWith("text/html");
        String html = callback.getResponse().getContentAsString();
        assertThat(html).contains("Комора").contains("Не вдалось підключити календар");
        assertThat(html).doesNotContain("Exception").doesNotContain("at com.silporestockai");

        assertThat(googleTokenRepository.findById(user.getId())).isEmpty();

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText()).contains("Не вдалось підключити");
    }

    private String startLogin() throws Exception {
        String location = mockMvc.perform(
                        get("/auth/google/start").param("userId", user.getId().toString()))
                .andReturn()
                .getResponse()
                .getHeader("Location");
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : URI.create(location).getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            query.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return query.get("state");
    }
}
