package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.exception.ApplicationException;
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
import java.util.List;
import java.util.Map;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UserProfileRepository userProfileRepository;
    private final MealPlanRepository mealPlanRepository;
    private final ClaudeApiClient claudeApiClient;
    private final Clock clock;
    private final String systemPrompt;

    public MealPlanService(
            UserProfileRepository userProfileRepository,
            MealPlanRepository mealPlanRepository,
            ClaudeApiClient claudeApiClient,
            Clock clock,
            @Value("classpath:prompts/meal-plan-system.txt") Resource systemPromptResource) {
        this.userProfileRepository = userProfileRepository;
        this.mealPlanRepository = mealPlanRepository;
        this.claudeApiClient = claudeApiClient;
        this.clock = clock;
        this.systemPrompt = read(systemPromptResource);
    }

    @Transactional
    public MealPlan generateWeeklyPlan(UUID userId) {
        return generate(userId, null);
    }

    private MealPlan generate(UUID userId, String adjustment) {
        UserProfile profile = userProfileRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ApplicationException(
                        HttpStatus.PRECONDITION_REQUIRED,
                        "user %s has no profile yet; onboarding has to finish first".formatted(userId)));

        String userPrompt = describe(profile, adjustment);
        WeeklyMealPlan plan = claudeApiClient.completeStructured(systemPrompt, userPrompt, WeeklyMealPlan.class);
        return persist(userId, plan);
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
    private String describe(UserProfile profile, String adjustment) {
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
        text.append("Обмеження та алергії: ")
                .append(joinOr(profile.getDietaryRestrictions(), "немає"))
                .append('\n');
        text.append("Не їдять: ")
                .append(joinOr(profile.getDislikedFoods(), "немає"))
                .append('\n');
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
