package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.exception.ApplicationException;
import com.silporestockai.exception.MealPlanGenerationException;
import com.silporestockai.model.PlannedDay;
import com.silporestockai.model.PlannedMeal;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private final Clock clock;
    private final String systemPrompt;

    public MealPlanService(
            UserProfileRepository userProfileRepository,
            MealPlanRepository mealPlanRepository,
            ClaudeApiClient claudeApiClient,
            InventoryTrendService inventoryTrendService,
            Clock clock,
            @Value("classpath:prompts/meal-plan-system.txt") Resource systemPromptResource) {
        this.userProfileRepository = userProfileRepository;
        this.mealPlanRepository = mealPlanRepository;
        this.claudeApiClient = claudeApiClient;
        this.inventoryTrendService = inventoryTrendService;
        this.clock = clock;
        this.systemPrompt = read(systemPromptResource);
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

        String userPrompt = describe(profile, adjustment, inventoryTrendService.getRemovalCandidates(userId));
        WeeklyMealPlan plan = claudeApiClient.completeStructured(systemPrompt, userPrompt, WeeklyMealPlan.class);
        List<String> defects = defectsOf(plan);
        if (!defects.isEmpty()) {
            // One retry, naming what was wrong. Re-sending the same prompt would be a coin flip, and the transport
            // retries in ClaudeApiClientImpl do not see this class of failure at all — the answer arrived fine, it is
            // the plan inside it that is unusable.
            log.warn("Claude returned an unusable plan for user {}: {}", userId, defects);
            plan = claudeApiClient.completeStructured(
                    systemPrompt, correctionOf(userPrompt, defects), WeeklyMealPlan.class);
            defects = defectsOf(plan);
            if (!defects.isEmpty()) {
                throw new MealPlanGenerationException(userId, defects);
            }
        }
        return persist(userId, plan);
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

    private MealPlan persist(UUID userId, WeeklyMealPlan plan) {
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
