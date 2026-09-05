package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
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

@DisplayName("free text, outside any active flow, is classified into an intent and dispatched")
class IntentRouterIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "4040:stub-bot-token";
    private static final long CHAT_ID = 15901L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

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

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_family",
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        MCP.reset();
        CLAUDE.reset();
        scheduledAdHocTaskRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
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
    void scheduledPurchaseIntentSchedulesRatherThanBuildsImmediately() throws Exception {
        CLAUDE.respondWithText("""
                {"intent":"AD_HOC_SCHEDULED_PURCHASE","confidence":0.9,\
                "themeDescription":"вино та сир зі знижкою","targetDateTimeIso":"2026-09-11T18:00:00Z"}""");

        sendText(1, "закажи до п'ятниці вино та сир по знижці");

        assertThat(scheduledAdHocTaskRepository.findAll()).hasSize(1);
        assertThat(scheduledAdHocTaskRepository.findAll().getFirst().getThemeDescription())
                .isEqualTo("вино та сир зі знижкою");
        // The confirmation says what will happen, not just echoes the theme back — and names no date, because
        // the task fires on the next sweep regardless of the deadline mentioned.
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText())
                .contains("найближчим часом")
                .contains("вино та сир зі знижкою")
                .doesNotContain("вересня");
    }

    @Test
    void lowConfidenceAsksAClarifyingQuestionInsteadOfGuessing() throws Exception {
        CLAUDE.respondWithText(
                "{\"intent\":\"UNKNOWN\",\"confidence\":0.2,\"themeDescription\":null,\"targetDateTimeIso\":null}");

        sendText(1, "щось незрозуміле бурмотіння");

        assertThat(TELEGRAM.sentMessages()).isNotEmpty();
        assertThat(scheduledAdHocTaskRepository.findAll()).isEmpty();
    }

    @Test
    void helpIntentRendersTheStaticInstructionMessage() throws Exception {
        CLAUDE.respondWithText(
                "{\"intent\":\"HELP\",\"confidence\":0.95,\"themeDescription\":null,\"targetDateTimeIso\":null}");

        sendText(1, "що ти вмієш?");

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Список");
    }

    @Test
    void uaOnlyIntentTogglesTheFlag() throws Exception {
        CLAUDE.respondWithText("""
                {"intent":"FILTER_UA_PRODUCER_ONLY","confidence":0.9,"themeDescription":null,\
                "targetDateTimeIso":null}""");

        sendText(1, "шукай тільки український виробник");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        assertThat(userProfileRepository.findByUserId(userId).orElseThrow().getOnlyUaProducer())
                .isTrue();
    }

    @Test
    void calendarViewIntentShowsTheDaySelectorNotTheShoppingList() throws Exception {
        CLAUDE.respondWithText(
                "{\"intent\":\"CALENDAR_VIEW\",\"confidence\":0.9,\"themeDescription\":null,\"targetDateTimeIso\":null}");

        sendText(1, "покажи календар");

        // No plan exists yet for this user, so the honest "no plan" message is what proves dispatch reached
        // CalendarViewService rather than some other handler.
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("немає");
    }

    @Test
    void blackoutIntentBuildsAnEmergencyCartFromFreeText() throws Exception {
        CLAUDE.respondWithText(
                "{\"intent\":\"BLACKOUT\",\"confidence\":0.92,\"themeDescription\":null,\"targetDateTimeIso\":null}");
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-b\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool(
                "silpo_find_products_batch",
                "{\"queries\":[{\"query\":\"консерви рибні\",\"products\":[{\"name\":\"консерви рибні\","
                        + "\"productId\":\"p-77\",\"branchId\":\"branch-7\"}]}]}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-b","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[{"productId":"p-77","name":"Шпроти","unit":"шт","quantity":1,"price":72}],\
                "total":72,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-b",\
                "checkoutMobileLink":"silpo://checkout/cart-b"}""");

        sendText(1, "світло вимкнули, немає струму");

        assertThat(TELEGRAM.sentMessages())
                .anyMatch(message -> message.path("text").asText().contains("без плити"));
        assertThat(conversationStateRepository
                        .findById(user.getTelegramChatId())
                        .orElseThrow()
                        .getCurrentFlow())
                .isEqualTo(com.silporestockai.model.ConversationFlow.CART_CONFIRMATION);
    }

    private static String classified(String intent) {
        return "{\"intent\":\"%s\",\"confidence\":0.9,\"themeDescription\":null,\"targetDateTimeIso\":null}"
                .formatted(intent);
    }

    /**
     * The product brief's flow 9 ends with "за командою «я в порядку» агент повертається до звичайного профілю"
     * — until now only the typed /normal did that. No mode is active here, so SpecialModeService's own
     * "already normal" answer is what proves the dispatch reached it.
     */
    @Test
    void specialModeEndIntentReachesTheCancelPathFromFreeText() throws Exception {
        CLAUDE.respondWithText(classified("SPECIAL_MODE_END"));

        sendText(1, "я в порядку, повертай звичайний раціон");

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Звичайний режим і так активний");
    }

    /** «Що треба докупити?» does what the typed /reorder does. Nothing is running low, so nothing is ordered. */
    @Test
    void reorderIntentStartsADeltaReorderFromFreeText() throws Exception {
        CLAUDE.respondWithText(classified("REORDER"));

        sendText(1, "що треба докупити?");

        assertThat(TELEGRAM.sentMessages())
                .anyMatch(message -> message.path("text").asText().contains("Дивлюсь, що треба докупити"));
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("нічого докуповувати");
    }

    /**
     * A concrete edit goes straight to the list builder with the person's sentence, skipping the "Що беремо на
     * цей тиждень?" opener that would have asked them to say it again.
     */
    @Test
    void listModifyIntentEditsTheListWithoutAskingTheOpeningQuestion() throws Exception {
        CLAUDE.respondWithTexts(
                classified("LIST_MODIFY"), "{\"items\":[{\"name\":\"Яйця С1\",\"quantity\":10,\"unit\":\"шт\"}]}");

        sendText(1, "додай яйця до списку");

        assertThat(CLAUDE.callCount()).isEqualTo(2);
        assertThat(CLAUDE.requests().getLast().toString()).contains("додай яйця до списку");
        String last = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(last).contains("Яйця С1").doesNotContain("фото чека");
    }

    /** No Google credentials in this context, so the honest "not configured" answer proves the dispatch. */
    @Test
    void calendarConnectIntentReachesTheGoogleCalendarOffer() throws Exception {
        CLAUDE.respondWithText(classified("CALENDAR_CONNECT"));

        sendText(1, "підключи гугл календар");

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Календар зараз не налаштований");
    }

    /** /start after onboarding is a "where am I" — it brings the keyboard back and never hits the classifier. */
    @Test
    void startAfterOnboardingBringsTheMenuBackWithoutAClassificationCall() throws Exception {
        sendText(1, "/start");

        assertThat(CLAUDE.callCount()).isZero();
        var sent = TELEGRAM.sentMessages().getLast();
        assertThat(sent.path("text").asText()).doesNotContain("Не зовсім зрозумів");
        assertThat(sent.path("reply_markup").path("keyboard").isArray()).isTrue();
    }

    @Test
    void existingSlashCommandsStillWorkUnchangedWithoutAClassificationCall() throws Exception {
        // The additive-rollout guarantee: /uaonly still reaches SpecialModeService directly, no
        // classification call happens for it at all.
        sendText(1, "/uaonly");

        assertThat(CLAUDE.callCount()).isZero();
        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        assertThat(userProfileRepository.findByUserId(userId).orElseThrow().getOnlyUaProducer())
                .isTrue();
    }
}
