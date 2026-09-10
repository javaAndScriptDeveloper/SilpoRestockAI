package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.SpecialModeService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Task 67. A household that cooks daily says it has no time this week, eats ready meals until the deadline
 * passes, and comes out the other side still recorded as a household that cooks daily.
 */
@DisplayName("crunch week overrides cooking time at read time and reverts on its own")
class CrunchWeekIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 9333L;
    private static final String BOT_TOKEN = "9333:stub-bot-token";
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SpecialModeService specialModeService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private MealPlanRepository mealPlanRepository;

    @Autowired
    private ShoppingListItemRepository shoppingListItemRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

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
        shoppingListItemRepository.deleteAll();
        mealPlanRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();

        user = userAccountService.findOrCreate(CHAT_ID);
        saveProfile(CookingTimePreference.COOKS_DAILY);
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        scriptSilpo();
    }

    private void saveProfile(CookingTimePreference preference) {
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .cookingTimePreference(preference)
                .build());
    }

    @Test
    void aCrunchWeekPlansReadyMealsWithoutRewritingTheProfile() {
        CLAUDE.respondWithText(curatedWeekJson());

        specialModeService.triggerCrunchWeek(user);

        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getSpecialMode()).isEqualTo(SpecialMode.CRUNCH_WEEK);
        assertThat(profile.getSpecialModeStartedAt()).isNotNull();
        assertThat(profile.getSpecialModeExpiresAt()).isNotNull();
        // The acceptance criterion of the whole task: the stored preference is what it always was.
        assertThat(profile.getCookingTimePreference()).isEqualTo(CookingTimePreference.COOKS_DAILY);
        // The fork is proved by which planner ran, not by the plan row: MealPlan.sourceType is @Transient.
        assertThat(plannerPrompts()).contains("меню готової їжі");
        assertThat(TELEGRAM.sentMessages().toString()).contains("готову їжу");
    }

    @Test
    void expiryRevertsToTheStoredPreferenceAndSaysSo() {
        CLAUDE.respondWithText(curatedWeekJson());
        specialModeService.triggerCrunchWeek(user);

        expireTheMode();
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());
        TELEGRAM.reset();

        assertThat(specialModeService.sweepExpired()).isEqualTo(1);

        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getSpecialMode()).isEqualTo(SpecialMode.NONE);
        assertThat(profile.getSpecialModeExpiresAt()).isNull();
        assertThat(profile.getCookingTimePreference()).isEqualTo(CookingTimePreference.COOKS_DAILY);
        // Back through the recipe planner, which is the observable proof the override is gone.
        assertThat(plannerPrompts()).doesNotContain("меню готової їжі");
        assertThat(TELEGRAM.sentMessages().toString()).contains("Тиждень запари закінчився");
    }

    /** «вже не запара, повертай як було» — task 43's SPECIAL_MODE_END, reused rather than duplicated. */
    @Test
    void theExistingEndIntentAlsoEndsACrunchWeekEarly() throws Exception {
        CLAUDE.respondWithText(curatedWeekJson());
        specialModeService.triggerCrunchWeek(user);
        CLAUDE.reset();
        CLAUDE.respondWithTexts("""
                {"intent":"SPECIAL_MODE_END","confidence":0.94,"themeDescription":null,"targetDateTimeIso":null}""", MealPlanIntegrationTest.fullWeekJson());
        TELEGRAM.reset();

        sendText(1, "вже не запара, повертай як було");

        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getSpecialMode()).isEqualTo(SpecialMode.NONE);
        assertThat(profile.getCookingTimePreference()).isEqualTo(CookingTimePreference.COOKS_DAILY);
        assertThat(plannerPrompts()).doesNotContain("меню готової їжі");
        assertThat(TELEGRAM.sentMessages().toString()).contains("запара позаду");
    }

    @Test
    void freeTextAboutADeadlineWeekTurnsTheModeOn() throws Exception {
        CLAUDE.respondWithTexts("""
                {"intent":"SPECIAL_MODE_CRUNCH_WEEK","confidence":0.93,"themeDescription":null,\
                "targetDateTimeIso":null}""", curatedWeekJson());

        sendText(1, "цей тиждень нема часу готувати, запара на роботі");

        assertThat(userProfileRepository
                        .findByUserId(user.getId())
                        .orElseThrow()
                        .getSpecialMode())
                .isEqualTo(SpecialMode.CRUNCH_WEEK);
    }

    /** Nothing to override, so nothing happens — and no plan is regenerated to say it. */
    @Test
    void aHouseholdAlreadyOnReadyMealsIsToldThereIsNothingToChange() {
        userProfileRepository.deleteAll();
        saveProfile(CookingTimePreference.READY_MEALS_ONLY);

        specialModeService.triggerCrunchWeek(user);

        assertThat(userProfileRepository
                        .findByUserId(user.getId())
                        .orElseThrow()
                        .getSpecialMode())
                .isNull();
        assertThat(CLAUDE.callCount()).isZero();
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("Ти й так на готовій їжі");
    }

    /** Said twice in one bad week: the answer is when the running one ends, not a restarted clock. */
    @Test
    void sayingItAgainDoesNotRestartTheClock() {
        CLAUDE.respondWithText(curatedWeekJson());
        specialModeService.triggerCrunchWeek(user);
        Instant expiresAt =
                userProfileRepository.findByUserId(user.getId()).orElseThrow().getSpecialModeExpiresAt();
        CLAUDE.reset();
        TELEGRAM.reset();

        specialModeService.triggerCrunchWeek(user);

        assertThat(userProfileRepository
                        .findByUserId(user.getId())
                        .orElseThrow()
                        .getSpecialModeExpiresAt())
                .isEqualTo(expiresAt);
        assertThat(CLAUDE.callCount()).isZero();
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("вже увімкнений");
    }

    /** One mode at a time, as task 25 already decided: a gastritis week is not also a crunch week. */
    @Test
    void refusesWhileAnotherModeIsActive() {
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());
        specialModeService.triggerGastritis(user);
        CLAUDE.reset();
        TELEGRAM.reset();

        specialModeService.triggerCrunchWeek(user);

        assertThat(userProfileRepository
                        .findByUserId(user.getId())
                        .orElseThrow()
                        .getSpecialMode())
                .isEqualTo(SpecialMode.MEDICAL_GASTRITIS_ACUTE);
        assertThat(CLAUDE.callCount()).isZero();
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("вже активний інший режим");
    }

    private void expireTheMode() {
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        profile.setSpecialModeExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        userProfileRepository.save(profile);
    }

    /**
     * Every system prompt the planner sent since the last {@code CLAUDE.reset()}.
     *
     * <p>Which fork ran cannot be read back off the stored plan — {@code MealPlan.sourceType} is {@code @Transient},
     * so a re-read row always says {@code RECIPE_DERIVED} — and the ready-meals planner has a system prompt of its
     * own. That prompt is the fork.
     */
    private String plannerPrompts() {
        return CLAUDE.requests().toString();
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

    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-c\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-c","branchId":"branch-9","companyId":"company-1","deliveryType":"delivery",\
                "items":[],"total":0,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-c"}""");
        MCP.respondToTool("silpo_find_products_batch", READY_MEAL_CANDIDATES);
    }

    /** Enough real ready-meal candidates for the curation fork to have something to choose from. */
    private static final String READY_MEAL_CANDIDATES = """
            {"queries":[{"query":"готові страви","products":[
            {"name":"Салат «Грецький» готовий","productId":"00000000-0000-4000-8000-000000000001","price":59.9},
            {"name":"Гречка з яловичиною готова страва","productId":"00000000-0000-4000-8000-000000000002","price":89.9},
            {"name":"Плов з куркою готовий","productId":"00000000-0000-4000-8000-000000000003","price":94.5},
            {"name":"Борщ готовий, порція","productId":"00000000-0000-4000-8000-000000000004","price":72},
            {"name":"Салат Цезар готовий","productId":"00000000-0000-4000-8000-000000000005","price":99},
            {"name":"Суп-пюре гарбузовий готовий","productId":"00000000-0000-4000-8000-000000000006","price":68},
            {"name":"Котлета по-київськи готова","productId":"00000000-0000-4000-8000-000000000007","price":85},
            {"name":"Сендвіч з куркою готовий","productId":"00000000-0000-4000-8000-000000000008","price":54}]}]}""".replace("\n", "");

    /** The curation answer: positions in the candidate list above, three meals a day for seven days. */
    private static String curatedWeekJson() {
        StringBuilder days = new StringBuilder();
        String[] dayNames = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"};
        for (int d = 0; d < dayNames.length; d++) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            days.append("""
                    {"day":"%s","meals":[{"type":"BREAKFAST","candidate":%d},\
                    {"type":"LUNCH","candidate":%d},{"type":"DINNER","candidate":%d}]}""".formatted(dayNames[d], d % 8 + 1, (d + 1) % 8 + 1, (d + 2) % 8 + 1));
        }
        return "{\"days\":[" + days + "]}";
    }
}
