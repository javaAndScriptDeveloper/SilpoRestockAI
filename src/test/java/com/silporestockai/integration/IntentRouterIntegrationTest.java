package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubSttServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    private static final StubSttServer STT = startStt();

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

    @Autowired
    private ConversationStateService conversationStateService;

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

    private static StubSttServer startStt() {
        try {
            return new StubSttServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the STT stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
        registry.add("stt.api-key", () -> "stub-stt-key");
        registry.add("stt.endpoint", STT::endpoint);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
        CLAUDE.close();
        STT.close();
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

    private void sendVoice(int updateId) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "voice":{"file_id":"voice-1","file_unique_id":"u1","duration":4,\
                                "mime_type":"audio/ogg"}}}""".formatted(updateId, updateId, CHAT_ID)))
                .andExpect(status().isOk());
    }

    private void sendPhoto(int updateId) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "photo":[{"file_id":"photo-1","file_unique_id":"p1","width":90,"height":90}]}}""".formatted(updateId, updateId, CHAT_ID)))
                .andExpect(status().isOk());
    }

    /** A voice note outside any flow is the same request as the typed sentence: transcribed, then classified. */
    @Test
    void aVoiceNoteOutsideAnyFlowIsTranscribedAndRoutedLikeText() throws Exception {
        STT.respondWith("що ти вмієш");
        CLAUDE.respondWithText(classified("HELP"));

        sendVoice(1);

        assertThat(CLAUDE.callCount()).isEqualTo(1);
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Кнопки внизу");
    }

    /** A photo with no conversation open opens the list builder with it — a fridge, a shelf, a receipt. */
    @Test
    void aPhotoOutsideAnyFlowBuildsAListFromIt() throws Exception {
        CLAUDE.respondWithText("{\"items\":[{\"name\":\"Молоко 2.5%\",\"quantity\":2,\"unit\":\"л\"}]}");

        sendPhoto(1);

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Молоко 2.5%");
        assertThat(conversationStateRepository
                        .findById(user.getTelegramChatId())
                        .orElseThrow()
                        .getCurrentFlow())
                .isEqualTo(com.silporestockai.model.ConversationFlow.LIST_BUILDING);
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

    /**
     * The state every household is actually in when they type something: a list is on screen with its
     * «Замовити / Змінити» keyboard under it, which parks {@code conversation_state} in
     * {@code LIST_BUILDING/AWAITING_APPROVAL} and never clears it. Nothing ever resolves that state on its own, so
     * from the first weekly plan onwards every free-text message was swallowed as "an edit to the list" and answered
     * with a regenerated list — the classifier was never reached at all. That is why «замов до п'ятниці вино та сир»
     * and «замов усе для карбонари» both came back looking like the same default weekly list: not a misclassification,
     * a message that never got classified.
     */
    @Test
    void aScheduledPurchaseStillReachesTheClassifierWithAListAlreadyOnScreen() throws Exception {
        conversationStateService.save(CHAT_ID, ConversationFlow.LIST_BUILDING, "AWAITING_APPROVAL", Map.of());
        CLAUDE.respondWithText("""
                {"intent":"AD_HOC_SCHEDULED_PURCHASE","confidence":0.9,\
                "themeDescription":"вино та сир","targetDateTimeIso":"2026-09-11T18:00:00Z"}""");

        sendText(1, "замов сир з вином на п'ятницю");

        assertThat(CLAUDE.requests().getFirst().toString()).doesNotContain("Поточний список треба змінити так");
        assertThat(scheduledAdHocTaskRepository.findAll()).hasSize(1);
        assertThat(scheduledAdHocTaskRepository.findAll().getFirst().getThemeDescription())
                .isEqualTo("вино та сир");
    }

    /** The second phrase from the same report, from the same parked state: a dish order, not a weekly list. */
    @Test
    void aDishOrderStillReachesTheClassifierWithAListAlreadyOnScreen() throws Exception {
        conversationStateService.save(CHAT_ID, ConversationFlow.LIST_BUILDING, "AWAITING_APPROVAL", Map.of());
        CLAUDE.respondWithText("""
                {"intent":"DISH_INGREDIENTS_ORDER","confidence":0.95,\
                "themeDescription":"карбонара","targetDateTimeIso":null}""");

        sendText(1, "замов усе для карбонари");

        assertThat(CLAUDE.requests().getFirst().toString()).doesNotContain("Поточний список треба змінити так");
        assertThat(TELEGRAM.sentMessages())
                .anyMatch(message -> message.path("text").asText().contains("Зберу все для «карбонара»"));
    }

    /**
     * The behaviour the swallowing was standing in for still has to work — it just goes through the classifier
     * now, which has a LIST_MODIFY intent for exactly this and hands the sentence to the same list builder.
     */
    @Test
    void aListEditFromTheApprovalScreenStillEditsTheList() throws Exception {
        conversationStateService.save(CHAT_ID, ConversationFlow.LIST_BUILDING, "AWAITING_APPROVAL", Map.of());
        CLAUDE.respondWithTexts(
                classified("LIST_MODIFY"), "{\"items\":[{\"name\":\"Яйця С1\",\"quantity\":10,\"unit\":\"шт\"}]}");

        sendText(1, "прибери молоко зі списку, додай яйця");

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Яйця С1");
    }

    /**
     * The other half of the rule: a step that really did ask a question keeps owning the answer to it. «Що беремо
     * на цей тиждень?» is a question, so the sentence after it is a list input and must never be classified —
     * routing it would answer a question the household was in the middle of answering.
     */
    @Test
    void theListBuilderStillOwnsTheAnswerToItsOwnOpeningQuestion() throws Exception {
        conversationStateService.save(CHAT_ID, ConversationFlow.LIST_BUILDING, "AWAITING_INPUT", Map.of());
        CLAUDE.respondWithText("{\"items\":[{\"name\":\"Молоко 2.5%\",\"quantity\":2,\"unit\":\"л\"}]}");

        sendText(1, "щось просте на тиждень для двох");

        assertThat(CLAUDE.callCount()).isEqualTo(1);
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Молоко 2.5%");
    }

    /**
     * The whole taxonomy, from the state a household is actually in: a list on screen. One case per intent, each
     * with the kind of sentence somebody would really type, asserting only the thing that was broken — that the
     * message reached the classifier at all. It fails loudly if any intent is ever silently swallowed by a flow
     * again, which is what made this bug look like sixteen separate misclassifications instead of one dispatch bug.
     *
     * <p>The two system prompts are the evidence: the classifier's opens «Ти класифікуєш повідомлення», the list
     * builder's «Ти складаєш список покупок». Which one Claude was asked first says which service got the message.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "AD_HOC_SCHEDULED_PURCHASE|замов сир з вином на п'ятницю",
                "REORDER|що треба докупити?",
                "SPECIAL_MODE_MEDICAL_GASTRITIS|я захворів, гастрит",
                "SPECIAL_MODE_LEANER|зроби раціон менш калорійним",
                "SPECIAL_MODE_MASS_GAIN|хочу набрати масу",
                "SPECIAL_MODE_END|повертаємось до звичайного раціону",
                "FILTER_UA_PRODUCER_ONLY|шукай тільки українського виробника",
                "HANGOVER_RELIEF|голова після вчорашнього",
                "BLACKOUT|світло вимкнули",
                "LIST_VIEW|покажи, що там у списку",
                "LIST_MODIFY|прибери молоко зі списку, додай яйця",
                "CALENDAR_VIEW|що їмо в середу?",
                "CALENDAR_CONNECT|підключи гугл календар",
                "PAST_ORDER_SEED|зроби список як минулого разу",
                "DISH_INGREDIENTS_ORDER|замов усе для карбонари",
                "HELP|що ти вмієш?",
                "UNKNOWN|кхм ну тобто це саме"
            })
    void everyIntentInTheTaxonomyStillReachesTheClassifierWithAListOnScreen(String intent, String phrase)
            throws Exception {
        conversationStateService.save(CHAT_ID, ConversationFlow.LIST_BUILDING, "AWAITING_APPROVAL", Map.of());
        CLAUDE.respondWithTexts(
                classified(intent), "{\"items\":[{\"name\":\"Яйця С1\",\"quantity\":10,\"unit\":\"шт\"}]}");

        sendText(1, phrase);

        assertThat(CLAUDE.requests()).isNotEmpty();
        assertThat(CLAUDE.requests().getFirst().toString())
                .as("%s must reach the intent classifier, not be swallowed as a list edit", intent)
                .contains("Ти класифікуєш повідомлення")
                .doesNotContain("Ти складаєш список покупок");
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
