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
import com.silporestockai.repository.GoogleOAuthTokenRepository;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CalendarIntegrationService;
import com.silporestockai.service.MealPlanHandoffService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.service.telegram.ShoppingListMessageService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubGoogleServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
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

/**
 * Task 71: the Google Calendar offer made during onboarding, before the first cart is confirmed.
 *
 * <p>What is being tested is the placement and the fact that neither answer costs anything — the OAuth flow itself
 * belongs to tasks 18 and 60 and is unchanged, which is why the accept path here goes through the very same
 * {@code /auth/google/callback} those tasks built.
 */
@DisplayName("the calendar is offered during onboarding, and neither answer blocks the first order")
class OnboardingCalendarOfferIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "8801:stub-bot-token";
    private static final long CHAT_ID = 8801L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubAnthropicServer CLAUDE = startClaude();
    private static final StubGoogleServer GOOGLE = startGoogle();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MealPlanHandoffService mealPlanHandoffService;

    @Autowired
    private CalendarIntegrationService calendarIntegrationService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private MealPlanRepository mealPlanRepository;

    @Autowired
    private ShoppingListItemRepository shoppingListItemRepository;

    @Autowired
    private GoogleOAuthTokenRepository googleTokenRepository;

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

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
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
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
        registry.add("google.calendar.client-id", () -> "stub-google-client");
        registry.add("google.calendar.client-secret", () -> "stub-google-secret");
        registry.add("google.calendar.token-endpoint", GOOGLE::tokenEndpoint);
        registry.add("google.calendar.api-url", GOOGLE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        CLAUDE.close();
        GOOGLE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        CLAUDE.reset();
        GOOGLE.reset();
        shoppingListItemRepository.deleteAll();
        mealPlanRepository.deleteAll();
        googleTokenRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();

        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .onlyUaProducer(false)
                .build());
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());
    }

    @Test
    void theFirstPlanCarriesTheOfferWithBothWaysOut() {
        mealPlanHandoffService.generateFirstPlan(user.getId());

        JsonNode offer = offerMessage();
        assertThat(offer.path("text").asText()).contains("Google Календарі");
        assertThat(buttonLabels(offer)).containsExactly("Підключити", "Пізніше");
        // The same OAuth entry point tasks 18 and 60 already own — a link, not a new mechanism of our own.
        assertThat(connectUrl(offer)).contains("client_id=stub-google-client").contains("state=");
    }

    /** The offer arrives under the list, so «Замовити» is still one tap away when it does. */
    @Test
    void theOfferComesAfterTheListAndNeverReplacesIt() {
        mealPlanHandoffService.generateFirstPlan(user.getId());

        List<JsonNode> sent = TELEGRAM.sentMessages();
        int list = indexOfCallback(sent, ShoppingListMessageService.CALLBACK_ORDER);
        int offer = indexOfCallback(sent, CalendarIntegrationService.CALLBACK_LATER);
        assertThat(list).isNotNegative();
        assertThat(offer).isGreaterThan(list);
        assertThat(sent.get(list).path("text").asText()).contains("Ось що пропоную взяти");
    }

    /** «Пізніше»: one line back, no token, nothing else touched, and the list is exactly where it was. */
    @Test
    void decliningCostsNothingAndLeavesTheOrderReachable() throws Exception {
        mealPlanHandoffService.generateFirstPlan(user.getId());
        TELEGRAM.reset();

        tapButton(1, CalendarIntegrationService.CALLBACK_LATER);

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText())
                .contains("Гаразд, без календаря")
                .contains("підключи гугл календар");
        assertThat(googleTokenRepository.findById(user.getId())).isEmpty();
        assertThat(shoppingListItemRepository.findAll()).isNotEmpty();
    }

    /**
     * The acceptance criterion the task exists for: accept during onboarding, finish the consent in the browser,
     * and the household's *first* delivery is the one that reaches the calendar.
     */
    @Test
    void acceptingBeforeTheFirstOrderPutsThatFirstDeliveryOnTheCalendar() throws Exception {
        mealPlanHandoffService.generateFirstPlan(user.getId());
        String state = stateOf(connectUrl(offerMessage()));

        mockMvc.perform(get("/auth/google/callback")
                        .param("code", "google-code")
                        .param("state", state))
                .andExpect(status().isOk());

        assertThat(googleTokenRepository.findById(user.getId())).isPresent();

        calendarIntegrationService.createDeliveryEvent(new OrderConfirmedEvent(
                user.getId(), UUID.randomUUID(), Instant.parse("2026-09-04T15:00:00Z"), "18:00–20:00", 7));

        assertThat(GOOGLE.insertedEvents()).hasSize(1);
        assertThat(GOOGLE.insertedEvents().getFirst().path("summary").asText()).contains("Сільпо");
    }

    /** Nothing to offer a household that already connected — and no second «підключити» to confuse them with. */
    @Test
    void anAlreadyConnectedCalendarIsNotOfferedAgain() {
        googleTokenRepository.save(GoogleOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stored-access-token"))
                .refreshToken(tokenCipher.encrypt("stored-refresh-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        mealPlanHandoffService.generateFirstPlan(user.getId());

        assertThat(indexOfCallback(TELEGRAM.sentMessages(), CalendarIntegrationService.CALLBACK_LATER))
                .isNegative();
    }

    private JsonNode offerMessage() {
        List<JsonNode> sent = TELEGRAM.sentMessages();
        int index = indexOfCallback(sent, CalendarIntegrationService.CALLBACK_LATER);
        assertThat(index).as("the onboarding calendar offer was never sent").isNotNegative();
        return sent.get(index);
    }

    private static int indexOfCallback(List<JsonNode> sent, String callbackData) {
        for (int i = 0; i < sent.size(); i++) {
            if (sent.get(i).path("reply_markup").toString().contains(callbackData)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> buttonLabels(JsonNode message) {
        return message.path("reply_markup").path("inline_keyboard").findValuesAsText("text");
    }

    private static String connectUrl(JsonNode message) {
        return message.path("reply_markup").path("inline_keyboard").findValuesAsText("url").stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("the offer carried no connect link"));
    }

    private static String stateOf(String authorizationUrl) {
        int at = authorizationUrl.indexOf("state=");
        assertThat(at).isNotNegative();
        String tail = authorizationUrl.substring(at + "state=".length());
        int end = tail.indexOf('&');
        return end < 0 ? tail : tail.substring(0, end);
    }

    private void tapButton(int updateId, String data) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"callback_query":{"id":"cb-%d",\
                                "from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}},\
                                "data":"%s"}}""".formatted(updateId, updateId, updateId, CHAT_ID, data)))
                .andExpect(status().isOk());
    }
}
