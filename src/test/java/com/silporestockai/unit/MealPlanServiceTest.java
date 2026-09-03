package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.AgeBracket;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.MealType;
import com.silporestockai.model.PlannedDay;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.PlannedMeal;
import com.silporestockai.model.WeeklyMealPlan;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.InventoryTrendService;
import com.silporestockai.service.MealPlanService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.io.ByteArrayResource;

class MealPlanServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void picksTheReadyMealsPromptForReadyMealsOnlyHouseholds() {
        assertGenerationUsesPrompt(CookingTimePreference.READY_MEALS_ONLY, "READY-MEALS-PROMPT");
    }

    @Test
    void picksTheRecipePromptForCooksDailyHouseholds() {
        assertGenerationUsesPrompt(CookingTimePreference.COOKS_DAILY, "RECIPE-PROMPT");
    }

    @Test
    void picksTheRecipePromptWhenNoPreferenceIsSet() {
        assertGenerationUsesPrompt(null, "RECIPE-PROMPT");
    }

    private void assertGenerationUsesPrompt(CookingTimePreference preference, String expectedPromptMarker) {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .cookingTimePreference(preference)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        when(mealPlanRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());

        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(validPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()));

        service.generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient)
                .completeStructured(systemPromptCaptor.capture(), anyString(), eq(WeeklyMealPlan.class));
        assertThat(systemPromptCaptor.getValue()).isEqualTo(expectedPromptMarker);
    }

    @Test
    void householdCompositionChangesTheGeneratedPromptText() {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile withKids = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .adultMaleCount(1)
                .adultFemaleCount(1)
                .childrenAgeBrackets(List.of(AgeBracket.AGE_0_3))
                .cookingTimePreference(CookingTimePreference.COOKS_DAILY)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(withKids));

        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        when(mealPlanRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(validPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()));

        service.generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient)
                .completeStructured(anyString(), userPromptCaptor.capture(), eq(WeeklyMealPlan.class));
        assertThat(userPromptCaptor.getValue()).contains("1 чоловіків, 1 жінок").contains("AGE_0_3");
    }

    private static WeeklyMealPlan validPlan() {
        List<PlannedIngredient> ingredients = List.of(new PlannedIngredient("Щось", BigDecimal.ONE, "шт", "Інше", null));
        List<PlannedMeal> meals = List.of(
                new PlannedMeal(MealType.BREAKFAST, "Сніданок", ingredients),
                new PlannedMeal(MealType.LUNCH, "Обід", ingredients),
                new PlannedMeal(MealType.DINNER, "Вечеря", ingredients));
        List<PlannedDay> days = Arrays.stream(DayOfWeek.values())
                .map(day -> new PlannedDay(day, meals))
                .toList();
        return new WeeklyMealPlan(days);
    }
}
