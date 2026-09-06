package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.exception.ApplicationException;
import com.silporestockai.exception.MealPlanGenerationException;
import com.silporestockai.model.CatalogCandidate;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.PlannedDay;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.PlannedMeal;
import com.silporestockai.model.PurchaseLine;
import com.silporestockai.model.RecipeWeek;
import com.silporestockai.model.ShoppingListSourceType;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.model.WeeklyMealPlan;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.UserProfileRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a household profile into a week of meals and the list to shop for it.
 *
 * <p>Two planners share this class. The recipe planner — every household that cooks, in any special mode —
 * answers with a {@link RecipeWeek}: meal names by day plus one shop-sized purchase list for the week. The
 * ready-meals planner (task 22) curates real catalog products and answers with a {@link WeeklyMealPlan} whose
 * per-meal ingredients carry product ids. Both are stored as a {@link WeeklyMealPlan}; see that record for how
 * {@code ShoppingListService} tells them apart.
 *
 * <p>The system prompts live in {@code resources/prompts/}: wording is the main thing that changes in a feature
 * like this, and a string literal would make every wording change a recompile. The recipe planner's output-format
 * rules are one shared file appended to every recipe prompt, so the four special-mode prompts cannot drift apart
 * on the shape they ask for.
 *
 * <p>Every generation INSERTs. Nothing is ever updated, because the diff between last week's plan and this one is a
 * product feature.
 */
@Slf4j
@Service
public class MealPlanService {

    /** Own mapper, as in {@code TelegramWebhookController}: Boot 4 carries both Jackson 2 and Jackson 3. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final int MINIMUM_MEALS_PER_DAY = 3;

    /**
     * Fewer lines than this is not a week's shopping. A household that cooks three meals a day for seven days
     * needs more than a handful of products even under the strictest diet; a shorter answer is a truncated or
     * lazy one, and the retry names it.
     */
    private static final int MINIMUM_PURCHASE_LINES = 5;

    private final UserProfileRepository userProfileRepository;
    private final MealPlanRepository mealPlanRepository;
    private final ClaudeApiClient claudeApiClient;
    private final InventoryTrendService inventoryTrendService;
    private final ReadyMealCatalogService readyMealCatalogService;
    private final Clock clock;
    private final String recipeSystemPrompt;
    private final String readyMealsSystemPrompt;
    private final String gastritisAcuteSystemPrompt;
    private final String gastritisDiet5SystemPrompt;
    private final String massGainSystemPrompt;
    private final String recipeOutputFormat;

    public MealPlanService(
            UserProfileRepository userProfileRepository,
            MealPlanRepository mealPlanRepository,
            ClaudeApiClient claudeApiClient,
            InventoryTrendService inventoryTrendService,
            Clock clock,
            @Value("classpath:prompts/meal-plan-system.txt") Resource recipeSystemPromptResource,
            @Value("classpath:prompts/meal-plan-ready-meals-system.txt") Resource readyMealsSystemPromptResource,
            @Value("classpath:prompts/meal-plan-gastritis-acute-system.txt")
                    Resource gastritisAcuteSystemPromptResource,
            @Value("classpath:prompts/meal-plan-gastritis-diet5-system.txt")
                    Resource gastritisDiet5SystemPromptResource,
            @Value("classpath:prompts/meal-plan-mass-gain-system.txt") Resource massGainSystemPromptResource,
            @Value("classpath:prompts/meal-plan-output-format.txt") Resource recipeOutputFormatResource,
            ReadyMealCatalogService readyMealCatalogService) {
        this.userProfileRepository = userProfileRepository;
        this.mealPlanRepository = mealPlanRepository;
        this.claudeApiClient = claudeApiClient;
        this.inventoryTrendService = inventoryTrendService;
        this.readyMealCatalogService = readyMealCatalogService;
        this.clock = clock;
        this.recipeSystemPrompt = read(recipeSystemPromptResource);
        this.readyMealsSystemPrompt = read(readyMealsSystemPromptResource);
        this.gastritisAcuteSystemPrompt = read(gastritisAcuteSystemPromptResource);
        this.gastritisDiet5SystemPrompt = read(gastritisDiet5SystemPromptResource);
        this.massGainSystemPrompt = read(massGainSystemPromptResource);
        this.recipeOutputFormat = read(recipeOutputFormatResource);
    }

    @Transactional
    public MealPlan generateWeeklyPlan(UUID userId) {
        return generate(userId, null);
    }

    /**
     * A new plan for the same week under an extra instruction — "мінус 200 ккал на день", "мас-набір".
     *
     * <p>Writes a new row rather than replacing the old one: showing what changed between two plans is a product
     * feature (brief flow #6), and it needs both of them.
     */
    @Transactional
    public MealPlan regenerateWithAdjustment(UUID userId, String adjustmentInstruction) {
        return generate(userId, adjustmentInstruction);
    }

    private MealPlan generate(UUID userId, String adjustment) {
        UserProfile profile = userProfileRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ApplicationException(
                        HttpStatus.PRECONDITION_REQUIRED,
                        "user %s has no profile yet; onboarding has to finish first".formatted(userId)));

        String specialPrompt = specialSystemPromptFor(profile.getSpecialMode());
        boolean readyMealsOnly =
                specialPrompt == null && profile.getCookingTimePreference() == CookingTimePreference.READY_MEALS_ONLY;
        List<String> untouched = inventoryTrendService.getRemovalCandidates(userId);

        if (readyMealsOnly) {
            return generateReadyMeals(userId, profile, adjustment, untouched);
        }
        String systemPrompt = (specialPrompt != null ? specialPrompt : recipeSystemPrompt) + recipeOutputFormat;
        return generateRecipes(userId, systemPrompt, describe(profile, adjustment, untouched));
    }

    /**
     * The recipe planner: names by day and one purchase list, validated and retried once.
     *
     * <p>The retry names what was wrong. Re-sending the same prompt would be a coin flip, and the transport
     * retries in {@code ClaudeApiClientImpl} do not see this class of failure at all — the answer arrived fine, it
     * is the plan inside it that is unusable.
     */
    private MealPlan generateRecipes(UUID userId, String systemPrompt, String userPrompt) {
        RecipeWeek week = claudeApiClient.completeStructured(systemPrompt, userPrompt, RecipeWeek.class);
        List<String> defects = defectsOf(week);
        if (!defects.isEmpty()) {
            log.warn("Claude returned an unusable plan for user {}: {}", userId, defects);
            week = claudeApiClient.completeStructured(
                    systemPrompt, correctionOf(userPrompt, defects), RecipeWeek.class);
            defects = defectsOf(week);
            if (!defects.isEmpty()) {
                throw new MealPlanGenerationException(userId, defects);
            }
        }
        log.info(
                "recipe plan for user {}: {} days, {} purchase lines",
                userId,
                week.days().size(),
                week.shoppingList().size());
        return persist(userId, asStoredPlan(week), ShoppingListSourceType.RECIPE_DERIVED);
    }

    /** The ready-meals planner (task 22): curation from real candidates, product ids stamped on afterwards. */
    private MealPlan generateReadyMeals(UUID userId, UserProfile profile, String adjustment, List<String> untouched) {
        List<CatalogCandidate> candidates = readyMealCatalogService.findCandidates(userId);
        if (candidates.isEmpty()) {
            // Zero real candidates means there is nothing for Claude to curate — asking it anyway would just
            // reproduce the original bug in a new form (an invented dish with no candidate behind it).
            throw new MealPlanGenerationException(
                    userId, List.of("Сільпо не має готових страв, які підходять під твої обмеження цього тижня"));
        }
        String userPrompt = curationPrompt(profile, adjustment, untouched, candidates);
        WeeklyMealPlan plan =
                claudeApiClient.completeStructured(readyMealsSystemPrompt, userPrompt, WeeklyMealPlan.class);
        List<String> defects = allDefectsOf(plan, candidates);
        if (!defects.isEmpty()) {
            log.warn("Claude returned an unusable ready-meals plan for user {}: {}", userId, defects);
            plan = claudeApiClient.completeStructured(
                    readyMealsSystemPrompt, correctionOf(userPrompt, defects), WeeklyMealPlan.class);
            defects = allDefectsOf(plan, candidates);
            if (!defects.isEmpty()) {
                throw new MealPlanGenerationException(userId, defects);
            }
        }
        return persist(userId, withResolvedProductIds(plan, candidates), ShoppingListSourceType.READY_MEAL_DIRECT);
    }

    /**
     * The stored shape of a recipe week: meals with no ingredients, and the purchase list alongside. Nothing from
     * the model reaches {@code productId} or {@code price} — the record it answered with has no such fields.
     */
    private static WeeklyMealPlan asStoredPlan(RecipeWeek week) {
        List<PlannedDay> days = week.days().stream()
                .map(day -> new PlannedDay(
                        day.day(),
                        day.meals().stream()
                                .map(meal -> new PlannedMeal(meal.type(), meal.name(), List.of()))
                                .toList()))
                .toList();
        List<PlannedIngredient> shoppingList = week.shoppingList().stream()
                .map(line -> new PlannedIngredient(
                        line.name().trim(), line.quantity(), line.unit(), line.category(), null, null))
                .toList();
        return new WeeklyMealPlan(days, shoppingList);
    }

    private static List<String> allDefectsOf(WeeklyMealPlan plan, List<CatalogCandidate> candidates) {
        List<String> defects = new ArrayList<>(defectsOf(plan));
        defects.addAll(candidateDefects(plan, candidates));
        return defects;
    }

    /**
     * Every ingredient name Claude returned that is not, character for character (case-insensitive), one of the real
     * candidates it was given — the check the acceptance criteria call "never invents outside the list".
     */
    private static List<String> candidateDefects(WeeklyMealPlan plan, List<CatalogCandidate> candidates) {
        if (plan == null || plan.days() == null) {
            return List.of();
        }
        Set<String> candidateNames = candidates.stream()
                .map(candidate -> normalise(candidate.name()))
                .collect(Collectors.toSet());
        List<String> defects = new ArrayList<>();
        for (PlannedDay day : plan.days()) {
            List<PlannedMeal> meals = day == null || day.meals() == null ? List.of() : day.meals();
            for (PlannedMeal meal : meals) {
                List<PlannedIngredient> ingredients =
                        meal == null || meal.ingredients() == null ? List.of() : meal.ingredients();
                for (PlannedIngredient ingredient : ingredients) {
                    String name = ingredient == null ? null : ingredient.name();
                    if (name == null || !candidateNames.contains(normalise(name))) {
                        defects.add("«%s» немає у списку реальних товарів Сільпо".formatted(name));
                    }
                }
            }
        }
        return defects;
    }

    /**
     * Stamps each ingredient's real productId on, matching by the same case-insensitive name rule as
     * {@link #candidateDefects}. Only ever called once that check has already passed — every name is guaranteed to
     * have a match.
     */
    private static WeeklyMealPlan withResolvedProductIds(WeeklyMealPlan plan, List<CatalogCandidate> candidates) {
        Map<String, CatalogCandidate> byName = candidates.stream()
                .collect(Collectors.toMap(
                        candidate -> normalise(candidate.name()), candidate -> candidate, (a, b) -> a));
        List<PlannedDay> days = plan.days().stream()
                .map(day -> new PlannedDay(
                        day.day(),
                        day.meals().stream()
                                .map(meal -> new PlannedMeal(
                                        meal.type(),
                                        meal.name(),
                                        meal.ingredients().stream()
                                                .map(ingredient -> {
                                                    CatalogCandidate candidate = Objects.requireNonNull(
                                                            byName.get(normalise(ingredient.name())));
                                                    return new PlannedIngredient(
                                                            ingredient.name(),
                                                            ingredient.quantity(),
                                                            ingredient.unit(),
                                                            ingredient.category(),
                                                            candidate.productId(),
                                                            candidate.price());
                                                })
                                                .toList()))
                                .toList()))
                .toList();
        return new WeeklyMealPlan(days);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String correctionOf(String userPrompt, List<String> defects) {
        return userPrompt + "\nПопередня відповідь була некоректна: " + String.join("; ", defects)
                + "\nПоверни повний план на всі 7 днів.";
    }

    /** Everything wrong with a recipe week, in the words the retry prompt uses. Empty means it is storable. */
    static List<String> defectsOf(RecipeWeek week) {
        if (week == null) {
            return List.of("у відповіді немає жодного дня");
        }
        List<String> defects = new ArrayList<>(dayDefects(
                week.days() == null
                        ? List.of()
                        : week.days().stream()
                                .map(day -> day == null
                                        ? null
                                        : new PlannedDay(
                                                day.day(),
                                                day.meals() == null
                                                        ? null
                                                        : day.meals().stream()
                                                                .map(meal -> meal == null
                                                                        ? null
                                                                        : new PlannedMeal(
                                                                                meal.type(), meal.name(), null))
                                                                .toList()))
                                .toList(),
                false));
        List<PurchaseLine> lines = week.shoppingList() == null ? List.of() : week.shoppingList();
        if (lines.isEmpty()) {
            defects.add("список покупок shoppingList порожній");
        } else if (lines.size() < MINIMUM_PURCHASE_LINES) {
            defects.add("у списку покупок лише %d позицій — це не тиждень".formatted(lines.size()));
        }
        for (PurchaseLine line : lines) {
            if (line == null || line.name() == null || line.name().isBlank()) {
                defects.add("рядок списку покупок без назви");
            } else if (line.quantity() == null || line.quantity().signum() <= 0) {
                defects.add("«%s» у списку покупок без кількості".formatted(line.name()));
            } else if (line.unit() == null || line.unit().isBlank()) {
                defects.add("«%s» у списку покупок без одиниці".formatted(line.name()));
            }
        }
        return defects;
    }

    /** Everything wrong with a stored-shape plan (the ready-meals path). Empty means the plan is storable. */
    private static List<String> defectsOf(WeeklyMealPlan plan) {
        if (plan == null) {
            return List.of("у відповіді немає жодного дня");
        }
        return dayDefects(plan.days(), true);
    }

    /**
     * The day-level checks both planners share: seven distinct days, enough meals, every meal named — and, for a
     * plan whose meals are supposed to carry ingredients, every meal with at least one.
     */
    private static List<String> dayDefects(List<PlannedDay> days, boolean ingredientsRequired) {
        List<String> defects = new ArrayList<>();
        if (days == null || days.isEmpty()) {
            defects.add("у відповіді немає жодного дня");
            return defects;
        }
        Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);
        for (PlannedDay day : days) {
            if (day == null || day.day() == null) {
                defects.add("день без назви дня тижня");
                continue;
            }
            if (!seen.add(day.day())) {
                defects.add("день %s повторюється".formatted(day.day()));
            }
            List<PlannedMeal> meals = day.meals() == null ? List.of() : day.meals();
            if (meals.size() < MINIMUM_MEALS_PER_DAY) {
                defects.add("у дні %s менше ніж %d прийоми їжі".formatted(day.day(), MINIMUM_MEALS_PER_DAY));
            }
            for (PlannedMeal meal : meals) {
                if (meal == null || meal.name() == null || meal.name().isBlank()) {
                    defects.add("страва без назви у дні %s".formatted(day.day()));
                } else if (ingredientsRequired
                        && (meal.ingredients() == null || meal.ingredients().isEmpty())) {
                    defects.add("страва «%s» без інгредієнтів".formatted(meal.name()));
                }
            }
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            if (!seen.contains(day)) {
                defects.add("у відповіді немає дня %s".formatted(day));
            }
        }
        return defects;
    }

    private MealPlan persist(UUID userId, WeeklyMealPlan plan, ShoppingListSourceType sourceType) {
        // convertValue to a raw Map rather than a TypeReference: an anonymous TypeReference subclass is a class in
        // this package, and ArchUnit requires every one of those to be named ...Service.
        @SuppressWarnings("unchecked")
        Map<String, Object> asJson = MAPPER.convertValue(plan, Map.class);
        MealPlan row = mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .weekStartDate(upcomingWeekStart())
                .plan(asJson)
                .createdAt(Instant.now())
                .build());
        row.setSourceType(sourceType);
        log.info("stored a weekly plan for user {} starting {}", userId, row.getWeekStartDate());
        return row;
    }

    /** The Monday the plan is for: today when today is Monday, otherwise the next one. */
    LocalDate upcomingWeekStart() {
        LocalDate today = LocalDate.now(clock);
        int daysAhead = (DayOfWeek.MONDAY.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        return today.plusDays(daysAhead);
    }

    /** {@code null} for {@code NONE}/{@code BLACKOUT} (blackout never reaches this service) — falls back to the usual recipe/ready-meals split. */
    private String specialSystemPromptFor(SpecialMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case MEDICAL_GASTRITIS_ACUTE -> gastritisAcuteSystemPrompt;
            case MEDICAL_DIET_TABLE_5 -> gastritisDiet5SystemPrompt;
            case MASS_GAIN -> massGainSystemPrompt;
            default -> null;
        };
    }

    /** Everything the model needs about this household, in the user message rather than the system prompt. */
    private String describe(UserProfile profile, String adjustment, List<String> untouched) {
        StringBuilder text = new StringBuilder("Склади меню на тиждень для цієї родини.\n");
        if (profile.getAdultMaleCount() != null || profile.getAdultFemaleCount() != null) {
            text.append("Дорослих: ")
                    .append(profile.getAdultMaleCount() == null ? 0 : profile.getAdultMaleCount())
                    .append(" чоловіків, ")
                    .append(profile.getAdultFemaleCount() == null ? 0 : profile.getAdultFemaleCount())
                    .append(" жінок.\n");
            if (profile.getChildrenAgeBrackets() != null
                    && !profile.getChildrenAgeBrackets().isEmpty()) {
                text.append("Дітей: ")
                        .append(profile.getChildrenAgeBrackets().size())
                        .append(", вікові групи: ")
                        .append(profile.getChildrenAgeBrackets().stream()
                                .map(Enum::name)
                                .collect(java.util.stream.Collectors.joining(", ")))
                        .append('\n');
            }
        } else {
            text.append("Людей удома: ")
                    .append(profile.getHouseholdSize() == null ? "невідомо" : profile.getHouseholdSize())
                    .append('\n');
            if (Boolean.TRUE.equals(profile.getHasKids())) {
                text.append("Діти: ")
                        .append(
                                profile.getKidsAges() == null
                                                || profile.getKidsAges().isEmpty()
                                        ? "є"
                                        : profile.getKidsAges())
                        .append('\n');
            }
        }
        // Quoted, never presented as a parsed list. «Все окрім молочки та бананів» read as a list says the
        // opposite of what was meant, and once produced a week of nothing but bananas.
        text.append("Про алергії та обмеження людина сказала: «")
                .append(joinOr(profile.getDietaryRestrictions(), "нема"))
                .append("»\n");
        text.append("Про те, чого вдома не їдять, людина сказала: «")
                .append(joinOr(profile.getDislikedFoods(), "нема"))
                .append("»\n");
        if (profile.getWeeklyBudget() != null) {
            text.append("Орієнтовний бюджет на тиждень: ")
                    .append(profile.getWeeklyBudget().toPlainString())
                    .append(" грн\n");
        }
        if (Boolean.TRUE.equals(profile.getOnlyUaProducer())) {
            text.append("Тільки продукти українського виробництва.\n");
        }
        if (profile.getSpecialMode() != null && profile.getSpecialMode() != SpecialMode.NONE) {
            text.append("Особливий режим харчування: ")
                    .append(profile.getSpecialMode().name())
                    .append('\n');
        }
        if (profile.getSpecialMode() == SpecialMode.MASS_GAIN) {
            if (profile.getTargetCalories() != null) {
                text.append("Цільова калорійність на день: ")
                        .append(profile.getTargetCalories())
                        .append(" ккал\n");
            }
            if (profile.getTargetProteinG() != null) {
                text.append("Цільовий білок на день: ")
                        .append(profile.getTargetProteinG())
                        .append(" г\n");
            }
        }
        if (!untouched.isEmpty()) {
            // The whole point of the trend counter: what the household demonstrably does not eat, said plainly.
            text.append("Не пропонуй ці продукти — їх стабільно не їдять: ")
                    .append(String.join(", ", untouched))
                    .append('\n');
        }
        if (adjustment != null && !adjustment.isBlank()) {
            text.append("Додаткова умова: ").append(adjustment.trim()).append('\n');
        }
        return text.toString();
    }

    /**
     * {@link #describe}'s household/constraints text, plus the real candidates Claude must choose from — never a
     * separate copy of the household text, since the constraints apply identically to both generation paths.
     */
    private String curationPrompt(
            UserProfile profile, String adjustment, List<String> untouched, List<CatalogCandidate> candidates) {
        StringBuilder text = new StringBuilder(describe(profile, adjustment, untouched));
        text.append("\nОсь список готових страв, які зараз реально є в Сільпо. Обирай страви ТІЛЬКИ з цього ")
                .append("списку і вказуй name страви ТОЧНО так, як він написаний нижче:\n");
        int position = 1;
        for (CatalogCandidate candidate : candidates) {
            text.append(position++).append(". ").append(candidate.name());
            if (candidate.price() != null) {
                text.append(" (").append(candidate.price().toPlainString()).append(" грн)");
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String joinOr(List<String> values, String fallback) {
        return values == null || values.isEmpty() ? fallback : String.join(", ", values);
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the meal plan system prompt", e);
        }
    }
}
