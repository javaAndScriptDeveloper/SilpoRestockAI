package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.exception.ApplicationException;
import com.silporestockai.exception.MealPlanGenerationException;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.MealPlanService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("a profile becomes a persisted weekly plan")
class MealPlanIntegrationTest extends AbstractIntegrationTest {

    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MealPlanService mealPlanService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private MealPlanRepository mealPlanRepository;

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        CLAUDE.reset();
        mealPlanRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    private UUID profiledUser(long chatId, List<String> restrictions, List<String> dislikes) {
        User user = userAccountService.findOrCreate(chatId);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(4)
                .hasKids(true)
                .kidsAges(List.of(3, 7))
                .dietaryRestrictions(restrictions)
                .dislikedFoods(dislikes)
                .weeklyBudget(new BigDecimal("2500"))
                .onlyUaProducer(false)
                .build());
        return user.getId();
    }

    /** A complete seven-day answer, the shape the service demands before it persists anything. */
    static String fullWeekJson() {
        StringBuilder days = new StringBuilder();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            days.append(dayJson(day.name()));
        }
        return "{\"days\":[" + days + "]}";
    }

    static String dayJson(String day) {
        return """
                {"day":"%s","meals":[\
                {"type":"BREAKFAST","name":"Вівсянка","ingredients":[{"name":"вівсяні пластівці","quantity":0.3,"unit":"кг"}]},\
                {"type":"LUNCH","name":"Курячий суп","ingredients":[{"name":"куряче стегно","quantity":0.5,"unit":"кг"}]},\
                {"type":"DINNER","name":"Гречка з овочами","ingredients":[{"name":"гречка","quantity":0.4,"unit":"кг"}]}]}""".formatted(day);
    }

    @Test
    void persistsSevenDaysWithThreeMealsEach() {
        UUID userId = profiledUser(8101L, List.of(), List.of());
        CLAUDE.respondWithText(fullWeekJson());

        MealPlan saved = mealPlanService.generateWeeklyPlan(userId);

        assertThat(CLAUDE.callCount()).isEqualTo(1);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getWeekStartDate().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(saved.getWeekStartDate()).isAfterOrEqualTo(LocalDate.now().minusDays(1));
        assertThat(mealPlanRepository.findById(saved.getId())).isPresent();

        @SuppressWarnings("unchecked")
        List<Object> days = (List<Object>) mealPlanRepository
                .findById(saved.getId())
                .orElseThrow()
                .getPlan()
                .get("days");
        assertThat(days).hasSize(7);
    }

    @Test
    void sendsTheProfileConstraintsToClaude() {
        UUID userId = profiledUser(8102L, List.of("вегетаріанство"), List.of("броколі"));
        CLAUDE.respondWithText(fullWeekJson());

        mealPlanService.generateWeeklyPlan(userId);

        String sent = CLAUDE.requests().getFirst().toString();
        assertThat(sent).contains("вегетаріанство").contains("броколі").contains("2500");
    }

    @Test
    void usesTheGastritisAcutePromptWhenTheProfileIsInThatSpecialMode() {
        UUID userId = profiledUser(8108L, List.of(), List.of());
        userProfileRepository.findByUserId(userId).ifPresent(profile -> {
            profile.setSpecialMode(com.silporestockai.model.SpecialMode.MEDICAL_GASTRITIS_ACUTE);
            userProfileRepository.save(profile);
        });
        CLAUDE.respondWithText(fullWeekJson());

        mealPlanService.regenerateWithAdjustment(userId, null);

        String sent = CLAUDE.requests().getFirst().toString();
        assertThat(sent).contains("гострим гастритом");
    }

    @Test
    void usesTheNormalPromptWhenSpecialModeIsNone() {
        UUID userId = profiledUser(8109L, List.of(), List.of());
        CLAUDE.respondWithText(fullWeekJson());

        mealPlanService.generateWeeklyPlan(userId);

        String sent = CLAUDE.requests().getFirst().toString();
        assertThat(sent).doesNotContain("гострим гастритом");
    }

    @Test
    void refusesToGenerateForAUserWithoutAProfile() {
        UUID userId = userAccountService.findOrCreate(8103L).getId();

        assertThatThrownBy(() -> mealPlanService.generateWeeklyPlan(userId)).isInstanceOf(ApplicationException.class);

        assertThat(CLAUDE.callCount()).isZero();
        assertThat(mealPlanRepository.count()).isZero();
    }

    /** A week with SATURDAY missing — well-formed JSON that would be wrong to store. */
    static String sixDayJson() {
        StringBuilder days = new StringBuilder();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day == DayOfWeek.SATURDAY) {
                continue;
            }
            if (!days.isEmpty()) {
                days.append(',');
            }
            days.append(dayJson(day.name()));
        }
        return "{\"days\":[" + days + "]}";
    }

    @Test
    void retriesOnceWhenADayIsMissingAndNamesTheDefect() {
        UUID userId = profiledUser(8104L, List.of(), List.of());
        CLAUDE.respondWithTexts(sixDayJson(), fullWeekJson());

        MealPlan saved = mealPlanService.generateWeeklyPlan(userId);

        assertThat(CLAUDE.callCount()).isEqualTo(2);
        assertThat(CLAUDE.requests().getLast().toString()).contains("SATURDAY");
        assertThat(mealPlanRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void failsWithoutStoringAnythingWhenTheSecondAnswerIsAlsoBroken() {
        UUID userId = profiledUser(8105L, List.of(), List.of());
        CLAUDE.respondWithTexts(sixDayJson(), sixDayJson());

        assertThatThrownBy(() -> mealPlanService.generateWeeklyPlan(userId))
                .isInstanceOf(MealPlanGenerationException.class)
                .hasMessageContaining("SATURDAY");

        assertThat(CLAUDE.callCount()).isEqualTo(2);
        assertThat(mealPlanRepository.count()).isZero();
    }

    @Test
    void rejectsADayThatHasTooFewMeals() {
        UUID userId = profiledUser(8106L, List.of(), List.of());
        String thinMonday = fullWeekJson().replace(dayJson("MONDAY"), """
                        {"day":"MONDAY","meals":[{"type":"BREAKFAST","name":"Вівсянка",\
                        "ingredients":[{"name":"вівсяні пластівці","quantity":0.3,"unit":"кг"}]}]}""");
        CLAUDE.respondWithTexts(thinMonday, fullWeekJson());

        mealPlanService.generateWeeklyPlan(userId);

        assertThat(CLAUDE.callCount()).isEqualTo(2);
        assertThat(CLAUDE.requests().getLast().toString()).contains("MONDAY");
    }

    @Test
    void regenerationAddsAPlanAndLeavesThePreviousOne() {
        UUID userId = profiledUser(8107L, List.of(), List.of());
        CLAUDE.respondWithText(fullWeekJson());
        MealPlan first = mealPlanService.generateWeeklyPlan(userId);

        MealPlan second = mealPlanService.regenerateWithAdjustment(userId, "мінус 200 ккал на день");

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getWeekStartDate()).isEqualTo(first.getWeekStartDate());
        assertThat(mealPlanRepository.count()).isEqualTo(2);
        assertThat(mealPlanRepository.findById(first.getId())).isPresent();
        assertThat(CLAUDE.requests().getLast().toString()).contains("мінус 200 ккал");

        assertThat(mealPlanRepository
                        .findFirstByUserIdAndWeekStartDateOrderByCreatedAtDesc(userId, second.getWeekStartDate())
                        .orElseThrow()
                        .getId())
                .isEqualTo(second.getId());
    }
}
