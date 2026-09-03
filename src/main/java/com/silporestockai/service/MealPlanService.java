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
 * Turns a household profile into a week of meals.
 *
 * <p>The system prompt lives in {@code resources/prompts/meal-plan-system.txt}: wording is the main thing that changes
 * in a feature like this, and a string literal would make every wording change a recompile.
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

    private final UserProfileRepository userProfileRepository;
    private final MealPlanRepository mealPlanRepository;
    private final ClaudeApiClient claudeApiClient;
    private final InventoryTrendService inventoryTrendService;
    private final ReadyMealCatalogService readyMealCatalogService;
    private final Clock clock;
    private final String recipeSystemPrompt;
    private final String readyMealsSystemPrompt;

    public MealPlanService(
            UserProfileRepository userProfileRepository,
            MealPlanRepository mealPlanRepository,
            ClaudeApiClient claudeApiClient,
            InventoryTrendService inventoryTrendService,
            Clock clock,
            @Value("classpath:prompts/meal-plan-system.txt") Resource recipeSystemPromptResource,
            @Value("classpath:prompts/meal-plan-ready-meals-system.txt") Resource readyMealsSystemPromptResource,
            ReadyMealCatalogService readyMealCatalogService) {
        this.userProfileRepository = userProfileRepository;
        this.mealPlanRepository = mealPlanRepository;
        this.claudeApiClient = claudeApiClient;
        this.inventoryTrendService = inventoryTrendService;
        this.readyMealCatalogService = readyMealCatalogService;
        this.clock = clock;
        this.recipeSystemPrompt = read(recipeSystemPromptResource);
        this.readyMealsSystemPrompt = read(readyMealsSystemPromptResource);
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

        boolean readyMealsOnly = profile.getCookingTimePreference() == CookingTimePreference.READY_MEALS_ONLY;
        String systemPrompt = readyMealsOnly ? readyMealsSystemPrompt : recipeSystemPrompt;
        List<String> untouched = inventoryTrendService.getRemovalCandidates(userId);

        List<CatalogCandidate> candidates = List.of();
        String userPrompt;
        if (readyMealsOnly) {
            candidates = readyMealCatalogService.findCandidates(userId);
            if (candidates.isEmpty()) {
                // Zero real candidates means there is nothing for Claude to curate — asking it anyway would just
                // reproduce the original bug in a new form (an invented dish with no candidate behind it).
                throw new MealPlanGenerationException(
                        userId, List.of("Сільпо не має готових страв, які підходять під ваші обмеження цього тижня"));
            }
            userPrompt = curationPrompt(profile, adjustment, untouched, candidates);
        } else {
            userPrompt = describe(profile, adjustment, untouched);
        }

        WeeklyMealPlan plan = claudeApiClient.completeStructured(systemPrompt, userPrompt, WeeklyMealPlan.class);
        List<String> defects = allDefectsOf(plan, readyMealsOnly, candidates);
        if (!defects.isEmpty()) {
            // One retry, naming what was wrong. Re-sending the same prompt would be a coin flip, and the transport
            // retries in ClaudeApiClientImpl do not see this class of failure at all — the answer arrived fine, it is
            // the plan inside it that is unusable.
            log.warn("Claude returned an unusable plan for user {}: {}", userId, defects);
            plan = claudeApiClient.completeStructured(
                    systemPrompt, correctionOf(userPrompt, defects), WeeklyMealPlan.class);
            defects = allDefectsOf(plan, readyMealsOnly, candidates);
            if (!defects.isEmpty()) {
                throw new MealPlanGenerationException(userId, defects);
            }
        }
        if (readyMealsOnly) {
            plan = withResolvedProductIds(plan, candidates);
        }
        return persist(
                userId,
                plan,
                readyMealsOnly ? ShoppingListSourceType.READY_MEAL_DIRECT : ShoppingListSourceType.RECIPE_DERIVED);
    }

    private static List<String> allDefectsOf(WeeklyMealPlan plan, boolean readyMealsOnly, List<CatalogCandidate> candidates) {
        List<String> defects = new ArrayList<>(defectsOf(plan));
        if (readyMealsOnly) {
            defects.addAll(candidateDefects(plan, candidates));
        }
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
        Set<String> candidateNames =
                candidates.stream().map(candidate -> normalise(candidate.name())).collect(Collectors.toSet());
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
                .collect(Collectors.toMap(candidate -> normalise(candidate.name()), candidate -> candidate, (a, b) -> a));
        List<PlannedDay> days = plan.days().stream()
                .map(day -> new PlannedDay(
                        day.day(),
                        day.meals().stream()
                                .map(meal -> new PlannedMeal(
                                        meal.type(),
                                        meal.name(),
                                        meal.ingredients().stream()
                                                .map(ingredient -> new PlannedIngredient(
                                                        ingredient.name(),
                                                        ingredient.quantity(),
                                                        ingredient.unit(),
                                                        ingredient.category(),
                                                        Objects.requireNonNull(byName.get(normalise(ingredient.name())))
                                                                .productId()))
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

    /** Everything wrong with a plan, in the words the retry prompt uses. Empty means the plan is storable. */
    private static List<String> defectsOf(WeeklyMealPlan plan) {
        List<String> defects = new ArrayList<>();
        if (plan == null || plan.days() == null || plan.days().isEmpty()) {
            defects.add("у відповіді немає жодного дня");
            return defects;
        }
        Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);
        for (PlannedDay day : plan.days()) {
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
                } else if (meal.ingredients() == null || meal.ingredients().isEmpty()) {
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
