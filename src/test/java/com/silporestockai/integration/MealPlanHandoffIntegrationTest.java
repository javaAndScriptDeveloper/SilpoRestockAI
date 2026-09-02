package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.MealPlanHandoffService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
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

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        CLAUDE.reset();
        shoppingListItemRepository.deleteAll();
        mealPlanRepository.deleteAll();
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
