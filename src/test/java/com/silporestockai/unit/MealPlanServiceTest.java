package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.AgeBracket;
import com.silporestockai.model.CatalogCandidate;
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
import com.silporestockai.service.ReadyMealCatalogService;
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

        boolean readyMealsOnly = preference == CookingTimePreference.READY_MEALS_ONLY;
        ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
        if (readyMealsOnly) {
            when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        }

        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(readyMealsOnly ? readyMealPlan() : validPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-ACUTE-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-DIET5-PROMPT".getBytes()),
                new ByteArrayResource("MASS-GAIN-PROMPT".getBytes()),
                readyMealCatalogService);

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
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-ACUTE-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-DIET5-PROMPT".getBytes()),
                new ByteArrayResource("MASS-GAIN-PROMPT".getBytes()),
                mock(ReadyMealCatalogService.class));

        service.generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient)
                .completeStructured(anyString(), userPromptCaptor.capture(), eq(WeeklyMealPlan.class));
        assertThat(userPromptCaptor.getValue()).contains("1 чоловіків, 1 жінок").contains("AGE_0_3");
    }

    @Test
    void readyMealsOnlyCurationPromptListsRealCandidatesWithPrice() {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .cookingTimePreference(CookingTimePreference.READY_MEALS_ONLY)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        when(mealPlanRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(readyMealPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-ACUTE-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-DIET5-PROMPT".getBytes()),
                new ByteArrayResource("MASS-GAIN-PROMPT".getBytes()),
                readyMealCatalogService);

        service.generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient)
                .completeStructured(anyString(), userPromptCaptor.capture(), eq(WeeklyMealPlan.class));
        assertThat(userPromptCaptor.getValue()).contains("Салат Цезар готовий").contains("89.9");
    }

    @Test
    void readyMealsOnlyResolvesTheRealProductIdOntoTheStoredPlan() {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .cookingTimePreference(CookingTimePreference.READY_MEALS_ONLY)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        ArgumentCaptor<com.silporestockai.entity.MealPlan> savedCaptor =
                ArgumentCaptor.forClass(com.silporestockai.entity.MealPlan.class);
        when(mealPlanRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(readyMealPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-ACUTE-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-DIET5-PROMPT".getBytes()),
                new ByteArrayResource("MASS-GAIN-PROMPT".getBytes()),
                readyMealCatalogService);

        service.generateWeeklyPlan(USER_ID);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        WeeklyMealPlan stored = mapper.convertValue(savedCaptor.getValue().getPlan(), WeeklyMealPlan.class);
        assertThat(stored.days().getFirst().meals())
                .allSatisfy(meal ->
                        assertThat(meal.ingredients().getFirst().productId()).isEqualTo("p-1"));
    }

    @Test
    void readyMealsOnlyRetriesWhenClaudeInventsAProductOutsideTheCandidateList() {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .cookingTimePreference(CookingTimePreference.READY_MEALS_ONLY)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        when(mealPlanRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        List<PlannedIngredient> invented = List.of(
                new PlannedIngredient("Страва, якої нема в каталозі", BigDecimal.ONE, "порція", "Готові страви", null));
        List<PlannedMeal> inventedMeals = List.of(
                new PlannedMeal(MealType.BREAKFAST, "Вигадка", invented),
                new PlannedMeal(MealType.LUNCH, "Вигадка", invented),
                new PlannedMeal(MealType.DINNER, "Вигадка", invented));
        WeeklyMealPlan inventedPlan = new WeeklyMealPlan(Arrays.stream(DayOfWeek.values())
                .map(day -> new PlannedDay(day, inventedMeals))
                .toList());
        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(inventedPlan)
                .thenReturn(readyMealPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-ACUTE-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-DIET5-PROMPT".getBytes()),
                new ByteArrayResource("MASS-GAIN-PROMPT".getBytes()),
                readyMealCatalogService);

        service.generateWeeklyPlan(USER_ID);

        Mockito.verify(claudeApiClient, Mockito.times(2))
                .completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class));
    }

    @Test
    void readyMealsOnlyThrowsWithoutCallingClaudeWhenNoCandidatesExist() {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .cookingTimePreference(CookingTimePreference.READY_MEALS_ONLY)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(List.of());
        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);

        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-ACUTE-PROMPT".getBytes()),
                new ByteArrayResource("GASTRITIS-DIET5-PROMPT".getBytes()),
                new ByteArrayResource("MASS-GAIN-PROMPT".getBytes()),
                readyMealCatalogService);

        assertThatThrownBy(() -> service.generateWeeklyPlan(USER_ID))
                .isInstanceOf(com.silporestockai.exception.MealPlanGenerationException.class);
        Mockito.verifyNoInteractions(claudeApiClient);
    }

    private static List<CatalogCandidate> oneCandidate() {
        return List.of(
                new CatalogCandidate("Салат Цезар готовий", "p-1", "company-3", "branch-7", new BigDecimal("89.90")));
    }

    private static WeeklyMealPlan readyMealPlan() {
        List<PlannedIngredient> ingredients =
                List.of(new PlannedIngredient("Салат Цезар готовий", BigDecimal.ONE, "порція", "Готові страви", null));
        List<PlannedMeal> meals = List.of(
                new PlannedMeal(MealType.BREAKFAST, "Салат Цезар готовий", ingredients),
                new PlannedMeal(MealType.LUNCH, "Салат Цезар готовий", ingredients),
                new PlannedMeal(MealType.DINNER, "Салат Цезар готовий", ingredients));
        List<PlannedDay> days = Arrays.stream(DayOfWeek.values())
                .map(day -> new PlannedDay(day, meals))
                .toList();
        return new WeeklyMealPlan(days);
    }

    private static WeeklyMealPlan validPlan() {
        List<PlannedIngredient> ingredients =
                List.of(new PlannedIngredient("Щось", BigDecimal.ONE, "шт", "Інше", null));
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
