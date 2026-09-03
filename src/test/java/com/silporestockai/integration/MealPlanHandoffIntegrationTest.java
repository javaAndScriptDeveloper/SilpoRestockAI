package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.MealPlanHandoffService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drives {@code generateFirstPlan} rather than the {@code @Async} listener that calls it: an async method is proxied
 * even when invoked directly, so every assertion after it would be a race. What the annotation buys — a webhook thread
 * that returns before the model does — is not observable from a test.
 */
@DisplayName("a finished onboarding produces the first plan on its own")
class MealPlanHandoffIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "555:stub-bot-token";
    private static final long CHAT_ID = 8201L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubAnthropicServer CLAUDE = startClaude();
    private static final StubMcpServer MCP = startMcp();

    @Autowired
    private MealPlanHandoffService mealPlanHandoffService;

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
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

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

    /** Only used by the READY_MEALS_ONLY tests — Step A of that path needs a cart context to search from. */
    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch"));
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        CLAUDE.close();
        MCP.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        CLAUDE.reset();
        MCP.reset();
        shoppingListItemRepository.deleteAll();
        mealPlanRepository.deleteAll();
        tokenRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    private UUID profiledUser() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .weeklyBudget(new BigDecimal("1500"))
                .onlyUaProducer(false)
                .build());
        return user.getId();
    }

    private static String fullWeekJson() {
        StringBuilder days = new StringBuilder();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            days.append("""
                    {"day":"%s","meals":[\
                    {"type":"BREAKFAST","name":"Вівсянка","ingredients":[{"name":"пластівці","quantity":0.3,"unit":"кг"}]},\
                    {"type":"LUNCH","name":"Борщ","ingredients":[{"name":"буряк","quantity":0.5,"unit":"кг"}]},\
                    {"type":"DINNER","name":"Рис з овочами","ingredients":[{"name":"рис","quantity":0.4,"unit":"кг"}]}]}""".formatted(day.name()));
        }
        return "{\"days\":[" + days + "]}";
    }

    private UUID readyMealsProfiledUser() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .cookingTimePreference(com.silporestockai.model.CookingTimePreference.READY_MEALS_ONLY)
                .build());
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-1\"}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3",\
                "deliveryType":"delivery","items":[]}""");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_find_products_batch", """
                {"queries":[{"query":"готові страви","products":[\
                {"name":"Салат Цезар готовий","productId":"p-1"},\
                {"name":"Борщ готовий, порція","productId":"p-2"}]}]}""");
        return user.getId();
    }

    private static String sparseReadyMealsWeekJson() {
        StringBuilder days = new StringBuilder();
        String[] names = {"Салат Цезар готовий", "Борщ готовий, порція"};
        String[] ids = {"p-1", "p-2"};
        int i = 0;
        for (DayOfWeek day : DayOfWeek.values()) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            String name = names[i % 2];
            String productId = ids[i % 2];
            i++;
            days.append("""
                    {"day":"%s","meals":[\
                    {"type":"BREAKFAST","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція",\
                    "category":"Готові страви","productId":"%s"}]},\
                    {"type":"LUNCH","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція",\
                    "category":"Готові страви","productId":"%s"}]},\
                    {"type":"DINNER","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція",\
                    "category":"Готові страви","productId":"%s"}]}]}""".formatted(day.name(), name, name, productId, name, name, productId, name, name, productId));
        }
        return "{\"days\":[" + days + "]}";
    }

    @Test
    void warnsWhenTheReadyMealsWeekHadToRepeatBecauseFewRealCandidatesExisted() {
        UUID userId = readyMealsProfiledUser();
        CLAUDE.respondWithText(sparseReadyMealsWeekJson());

        mealPlanHandoffService.generateFirstPlan(userId);

        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText()).contains("не так багато готових страв");
    }

    @Test
    void generatesAPlanAndTellsTheUserWhatIsOnMonday() {
        UUID userId = profiledUser();
        CLAUDE.respondWithText(fullWeekJson());

        mealPlanHandoffService.generateFirstPlan(userId);

        assertThat(mealPlanRepository.count()).isEqualTo(1);
        // Three distinct ingredients across the week, each repeated every day: the list is the collapsed form.
        assertThat(shoppingListItemRepository.count()).isEqualTo(3);
        // The plan announcement is the first message, not the last: the hand-off goes straight on to show the
        // list for approval rather than building a cart on its own — nothing reaches Silpo until a person agrees.
        String message = TELEGRAM.sentMessages().getFirst().path("text").asText();
        assertThat(message).contains("Вівсянка").contains("Борщ").contains("3 позицій");
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText())
                .contains("Ось що пропоную взяти")
                .contains("Всього 3 позиції");
    }

    @Test
    void saysSoRatherThanFailingSilentlyWhenGenerationBreaks() {
        UUID userId = profiledUser();
        CLAUDE.respondWithText("{\"days\":[]}");

        mealPlanHandoffService.generateFirstPlan(userId);

        assertThat(mealPlanRepository.count()).isZero();
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("не вдалось");
    }

    @Test
    void ignoresAnEventForAUserThatNoLongerExists() {
        mealPlanHandoffService.generateFirstPlan(UUID.randomUUID());

        assertThat(mealPlanRepository.count()).isZero();
        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }
}
