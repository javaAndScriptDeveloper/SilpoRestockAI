package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.entity.GoogleOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.OrderConfirmedEvent;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.GoogleOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CalendarIntegrationService;
import com.silporestockai.service.GoogleAuthService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubGoogleServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("a confirmed delivery lands in the calendar of whoever connected one")
class CalendarIntegrationIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "999:stub-bot-token";
    private static final long CHAT_ID = 11201L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubGoogleServer GOOGLE = startGoogle();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CalendarIntegrationService calendarIntegrationService;

    @Autowired
    private GoogleAuthService googleAuthService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private GoogleOAuthTokenRepository googleTokenRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenCipher tokenCipher;

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
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();

        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
    }

    private void connectCalendar() {
        googleTokenRepository.save(GoogleOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stored-access-token"))
                .refreshToken(tokenCipher.encrypt("stored-refresh-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    private OrderConfirmedEvent confirmedOrder(Instant deliveryStartsAt) {
        return new OrderConfirmedEvent(user.getId(), UUID.randomUUID(), deliveryStartsAt, "18:00–20:00", 7);
    }

    private void sendText(int updateId, String text) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "text":"%s"}}""".formatted(updateId, updateId, CHAT_ID, text)))
                .andExpect(status().isOk());
    }

    @Test
    void writesTheDeliveryWindowIntoAConnectedCalendar() {
        connectCalendar();

        calendarIntegrationService.createDeliveryEvent(confirmedOrder(Instant.parse("2026-09-04T15:00:00Z")));

        assertThat(GOOGLE.insertedEvents()).hasSize(1);
        JsonNode event = GOOGLE.insertedEvents().getFirst();
        assertThat(event.path("summary").asText()).contains("Сільпо");
        // 15:00 UTC is 18:00 in Kyiv, which is the window the user was shown.
        assertThat(event.path("start").path("dateTime").asText()).startsWith("2026-09-04T18:00:00");
        assertThat(event.path("end").path("dateTime").asText()).startsWith("2026-09-04T20:00:00");
        assertThat(event.path("description").asText()).contains("7 позицій");
        assertThat(GOOGLE.authorizationHeaders()).containsExactly("Bearer stored-access-token");
    }

    @Test
    void aUserWithNoCalendarIsSimplyLeftAlone() {
        calendarIntegrationService.createDeliveryEvent(confirmedOrder(Instant.parse("2026-09-04T15:00:00Z")));

        assertThat(GOOGLE.insertedEvents()).isEmpty();
        assertThat(GOOGLE.tokenRequests()).isEmpty();
    }

    @Test
    void anOrderWithNoReadableSlotTimeCreatesNothing() {
        connectCalendar();

        calendarIntegrationService.createDeliveryEvent(confirmedOrder(null));

        assertThat(GOOGLE.insertedEvents()).isEmpty();
    }

    @Test
    void aRefusedCalendarApiIsALogLineNotAFailure() {
        connectCalendar();
        GOOGLE.failEvents(500);

        // The groceries are ordered either way; nothing may propagate out of here.
        calendarIntegrationService.createDeliveryEvent(confirmedOrder(Instant.parse("2026-09-04T15:00:00Z")));

        assertThat(GOOGLE.insertedEvents()).hasSize(1);
    }

    @Test
    void anExpiredTokenIsRefreshedBeforeTheEventIsWritten() {
        googleTokenRepository.save(GoogleOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stale-access-token"))
                .refreshToken(tokenCipher.encrypt("stored-refresh-token"))
                .expiresAt(Instant.now().minusSeconds(60))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        calendarIntegrationService.createDeliveryEvent(confirmedOrder(Instant.parse("2026-09-04T15:00:00Z")));

        assertThat(GOOGLE.tokenRequests()).singleElement().asString().contains("grant_type=refresh_token");
        assertThat(GOOGLE.authorizationHeaders()).containsExactly("Bearer stub-google-access");
    }

    @Test
    void theCallbackStoresTokensAsCiphertextNotPlaintext() throws Exception {
        String url = googleAuthService.buildAuthorizationUrl(user.getId());
        String state = url.substring(url.indexOf("state=") + "state=".length());

        mockMvc.perform(get("/auth/google/callback").param("code", "auth-code").param("state", state))
                .andExpect(status().isOk());

        GoogleOAuthToken stored = googleTokenRepository.findById(user.getId()).orElseThrow();
        assertThat(stored.getAccessToken()).isNotEqualTo("stub-google-access");
        assertThat(tokenCipher.decrypt(stored.getAccessToken())).isEqualTo("stub-google-access");
        assertThat(tokenCipher.decrypt(stored.getRefreshToken())).isEqualTo("stub-google-refresh");
    }

    @Test
    void theCalendarCommandOffersAConsentLinkAndThenSaysItIsConnected() throws Exception {
        sendText(1, "/calendar");

        JsonNode offer = TELEGRAM.sentMessages().getLast();
        assertThat(offer.toString()).contains("accounts.google.com");

        connectCalendar();
        sendText(2, "/calendar");

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("уже підключено");
    }
}
