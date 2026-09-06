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
import com.silporestockai.model.PurchaseLine;
import com.silporestockai.model.RecipeDay;
import com.silporestockai.model.RecipeMeal;
import com.silporestockai.model.RecipeWeek;
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
    private static final String OUTPUT_FORMAT = "\nOUTPUT-FORMAT";

    private final UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
    private final MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
    private final InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
    private final ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
    private final ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
    private final ArgumentCaptor<com.silporestockai.entity.MealPlan> savedCaptor =
            ArgumentCaptor.forClass(com.silporestockai.entity.MealPlan.class);

    private MealPlanService service() {
        when(mealPlanRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        return new MealPlanService(
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
                new ByteArrayResource(OUTPUT_FORMAT.getBytes()),
                readyMealCatalogService);
    }

    private void profile(CookingTimePreference preference) {
        when(userProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(UserProfile.builder()
                        .id(UUID.randomUUID())
                        .userId(USER_ID)
                        .cookingTimePreference(preference)
                        .build()));
    }

    @Test
    void picksTheReadyMealsPromptForReadyMealsOnlyHouseholds() {
        profile(CookingTimePreference.READY_MEALS_ONLY);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(readyMealPlan());

        service().generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient)
                .completeStructured(systemPrompt.capture(), anyString(), eq(WeeklyMealPlan.class));
        assertThat(systemPrompt.getValue()).isEqualTo("READY-MEALS-PROMPT");
    }

    @Test
    void picksTheRecipePromptForCooksDailyHouseholds() {
        assertRecipeGenerationUsesPrompt(CookingTimePreference.COOKS_DAILY, "RECIPE-PROMPT");
    }

    @Test
    void picksTheRecipePromptWhenNoPreferenceIsSet() {
        assertRecipeGenerationUsesPrompt(null, "RECIPE-PROMPT");
    }

    /** The recipe planner's system prompt is the mode prompt plus the one shared output-format block. */
    private void assertRecipeGenerationUsesPrompt(CookingTimePreference preference, String expectedPromptMarker) {
        profile(preference);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(RecipeWeek.class)))
                .thenReturn(validRecipeWeek());

        service().generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient).completeStructured(systemPrompt.capture(), anyString(), eq(RecipeWeek.class));
        assertThat(systemPrompt.getValue()).isEqualTo(expectedPromptMarker + OUTPUT_FORMAT);
    }

    @Test
    void householdCompositionChangesTheGeneratedPromptText() {
        when(userProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(UserProfile.builder()
                        .id(UUID.randomUUID())
                        .userId(USER_ID)
                        .adultMaleCount(1)
                        .adultFemaleCount(1)
                        .childrenAgeBrackets(List.of(AgeBracket.AGE_0_3))
                        .cookingTimePreference(CookingTimePreference.COOKS_DAILY)
                        .build()));
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(RecipeWeek.class)))
                .thenReturn(validRecipeWeek());

        service().generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient).completeStructured(anyString(), userPrompt.capture(), eq(RecipeWeek.class));
        assertThat(userPrompt.getValue()).contains("1 чоловіків, 1 жінок").contains("AGE_0_3");
    }

    /**
     * The recipe planner answers with a purchase list, not per-meal ingredients — that is what is stored, and it
     * is what the shopping list is derived from. The meals keep names only. Nothing the model said can reach
     * {@code productId} or {@code price}: the record it answered with has no such fields.
     */
    @Test
    void recipePathStoresThePurchaseListAndMealNamesWithoutIngredientsOrProductIds() {
        profile(CookingTimePreference.COOKS_BATCH);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(RecipeWeek.class)))
                .thenReturn(validRecipeWeek());

        service().generateWeeklyPlan(USER_ID);

        WeeklyMealPlan stored = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .convertValue(savedCaptor.getValue().getPlan(), WeeklyMealPlan.class);
        assertThat(stored.hasShoppingList()).isTrue();
        assertThat(stored.shoppingList()).extracting(PlannedIngredient::name).contains("Гречка", "Куряче філе");
        assertThat(stored.shoppingList()).allSatisfy(line -> {
            assertThat(line.productId()).isNull();
            assertThat(line.price()).isNull();
        });
        assertThat(stored.days()).hasSize(7);
        assertThat(stored.days().getFirst().meals())
                .extracting(PlannedMeal::name)
                .containsExactly("Вівсянка", "Борщ", "Гречка з куркою");
        assertThat(stored.days())
                .allSatisfy(day -> assertThat(day.meals())
                        .allSatisfy(meal -> assertThat(meal.ingredients()).isEmpty()));
    }

    @Test
    void recipePathRetriesOnceNamingAMissingPurchaseListThenStoresTheGoodAnswer() {
        profile(CookingTimePreference.COOKS_DAILY);
        RecipeWeek noList = new RecipeWeek(validRecipeWeek().days(), List.of());
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(RecipeWeek.class)))
                .thenReturn(noList)
                .thenReturn(validRecipeWeek());

        service().generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient, Mockito.times(2))
                .completeStructured(anyString(), userPrompt.capture(), eq(RecipeWeek.class));
        assertThat(userPrompt.getAllValues().getLast()).contains("shoppingList порожній");
    }

    @Test
    void recipePathRejectsAPurchaseLineWithoutAQuantity() {
        profile(CookingTimePreference.COOKS_DAILY);
        RecipeWeek broken = new RecipeWeek(
                validRecipeWeek().days(),
                List.of(
                        new PurchaseLine("Гречка", null, "кг", "Крупи і бакалія"),
                        new PurchaseLine("Молоко", BigDecimal.ONE, "л", "Молочні продукти"),
                        new PurchaseLine("Хліб", BigDecimal.ONE, "шт", "Хлібобулочні вироби"),
                        new PurchaseLine("Яйця курячі", BigDecimal.TEN, "шт", "Яйця"),
                        new PurchaseLine("Цибуля", BigDecimal.ONE, "кг", "Овочі і фрукти")));
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(RecipeWeek.class)))
                .thenReturn(broken)
                .thenReturn(broken);

        assertThatThrownBy(() -> service().generateWeeklyPlan(USER_ID))
                .isInstanceOf(com.silporestockai.exception.MealPlanGenerationException.class)
                .hasMessageContaining("«Гречка» у списку покупок без кількості");
    }

    @Test
    void readyMealsOnlyCurationPromptListsRealCandidatesWithPrice() {
        profile(CookingTimePreference.READY_MEALS_ONLY);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(readyMealPlan());

        service().generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient).completeStructured(anyString(), userPrompt.capture(), eq(WeeklyMealPlan.class));
        assertThat(userPrompt.getValue()).contains("Салат Цезар готовий").contains("89.9");
    }

    @Test
    void readyMealsOnlyResolvesTheRealProductIdOntoTheStoredPlan() {
        profile(CookingTimePreference.READY_MEALS_ONLY);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(readyMealPlan());

        service().generateWeeklyPlan(USER_ID);

        WeeklyMealPlan stored = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .convertValue(savedCaptor.getValue().getPlan(), WeeklyMealPlan.class);
        assertThat(stored.hasShoppingList()).isFalse();
        assertThat(stored.days().getFirst().meals())
                .allSatisfy(meal ->
                        assertThat(meal.ingredients().getFirst().productId()).isEqualTo("p-1"));
        // Task 39: the candidate's price rides along, so the list can be priced before any cart exists.
        assertThat(stored.days().getFirst().meals())
                .allSatisfy(meal -> assertThat(meal.ingredients().getFirst().price())
                        .isEqualByComparingTo(oneCandidate().getFirst().price()));
    }

    @Test
    void readyMealsOnlyRetriesWhenClaudeInventsAProductOutsideTheCandidateList() {
        profile(CookingTimePreference.READY_MEALS_ONLY);
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
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(inventedPlan)
                .thenReturn(readyMealPlan());

        service().generateWeeklyPlan(USER_ID);

        Mockito.verify(claudeApiClient, Mockito.times(2))
                .completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class));
    }

    @Test
    void readyMealsOnlyThrowsWithoutCallingClaudeWhenNoCandidatesExist() {
        profile(CookingTimePreference.READY_MEALS_ONLY);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service().generateWeeklyPlan(USER_ID))
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

    private static RecipeWeek validRecipeWeek() {
        List<RecipeMeal> meals = List.of(
                new RecipeMeal(MealType.BREAKFAST, "Вівсянка"),
                new RecipeMeal(MealType.LUNCH, "Борщ"),
                new RecipeMeal(MealType.DINNER, "Гречка з куркою"));
        List<RecipeDay> days = Arrays.stream(DayOfWeek.values())
                .map(day -> new RecipeDay(day, meals))
                .toList();
        return new RecipeWeek(
                days,
                List.of(
                        new PurchaseLine("Вівсяні пластівці", new BigDecimal("0.5"), "кг", "Крупи і бакалія"),
                        new PurchaseLine("Гречка", BigDecimal.ONE, "кг", "Крупи і бакалія"),
                        new PurchaseLine("Куряче філе", new BigDecimal("1.2"), "кг", "М'ясо і птиця"),
                        new PurchaseLine("Буряк", BigDecimal.ONE, "кг", "Овочі і фрукти"),
                        new PurchaseLine("Молоко", new BigDecimal("2"), "л", "Молочні продукти")));
    }
}
