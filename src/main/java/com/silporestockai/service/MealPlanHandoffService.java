package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.model.OnboardingCompletedEvent;
import com.silporestockai.model.PlannedDay;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.PlannedMeal;
import com.silporestockai.model.PriceEstimate;
import com.silporestockai.model.ShoppingListSourceType;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.WeeklyMealPlan;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.ShoppingListMessageService;
import com.silporestockai.service.telegram.TelegramOutboundService;
import com.silporestockai.utils.DayLabels;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Turns the end of onboarding into the household's first weekly plan.
 *
 * <p>Asynchronous on purpose: the event is published on the Telegram webhook thread, generation takes tens of seconds
 * against the real API, and Telegram re-delivers any update it does not get a prompt answer for.
 *
 * <p>Nothing propagates out of here. An async listener that throws fails into a log line nobody reads, so a failure
 * becomes one plain sentence to the user instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealPlanHandoffService {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /**
     * The tap that asks for the plan again after a failure. Self-contained — it names no draft and needs no
     * {@code conversation_state} — so the routing layer dispatches it globally, like every other such tap.
     */
    public static final String CALLBACK_RETRY = "plan:retry";

    private final MealPlanService mealPlanService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final UserRepository userRepository;
    private final TelegramOutboundService telegramOutboundService;
    private final ShoppingListPriceEstimateService priceEstimateService;
    private final ShoppingListMessageService shoppingListMessageService;

    /**
     * The listener itself does nothing but leave the publishing thread. Everything it would otherwise do lives in
     * {@link #generateFirstPlan(UUID)}, which is directly callable — an {@code @Async} method is proxied even when
     * called from a test, so a test of the listener could only ever race with it.
     */
    // Named executor, not the bare annotation: @EnableScheduling contributes a TaskScheduler that is also an
    // Executor, so an unqualified @Async would find two candidates and quietly fall back to a new thread per call.
    @Async("applicationTaskExecutor")
    @EventListener
    public void onOnboardingCompleted(OnboardingCompletedEvent event) {
        generateFirstPlan(event.userId());
    }

    /** The «Спробувати ще раз» tap after a failed plan: say so, then the same generation as the first time. */
    public void retry(User user) {
        telegramOutboundService.sendMessage(user.getTelegramChatId(), "Складаю план ще раз — хвилинку.");
        generateFirstPlan(user.getId());
    }

    /** Generates, stores and announces the first weekly plan. Runs on the caller's thread. */
    public void generateFirstPlan(UUID userId) {
        userRepository
                .findById(userId)
                .ifPresentOrElse(
                        user -> {
                            try {
                                MealPlan plan = mealPlanService.generateWeeklyPlan(userId);
                                List<ShoppingListItem> list =
                                        shoppingListService.deriveFromMealPlan(plan.getId(), plan.getSourceType());
                                telegramOutboundService.sendMessage(
                                        user.getTelegramChatId(),
                                        summarise(plan, list.size(), priceEstimateService.estimate(userId, list)));
                                // Never straight to a cart. Eighty-four bananas went through unseen once; the
                                // list is shown and ordered only after somebody agrees to it.
                                shoppingListBuilderService.present(user, list);
                            } catch (RuntimeException e) {
                                log.error("could not generate the first plan for user {}", userId, e);
                                // A button, not a promise. «Спробую ще раз трохи пізніше» used to be said here,
                                // and nothing ever did — a household whose first plan failed had no way to ask
                                // for another one short of re-editing their Анкета.
                                telegramOutboundService.sendMessageWithButtons(
                                        user.getTelegramChatId(),
                                        "План скласти не вдалось. Спробуємо ще раз?",
                                        List.of(TelegramButton.callback("Спробувати ще раз", CALLBACK_RETRY)));
                            }
                        },
                        () -> log.warn("onboarding completed for unknown user {}", userId));
    }

    private static final int MINIMUM_DISTINCT_READY_MEALS = 7;

    /**
     * The week is ready, and here it is, one line a day. It used to show Monday alone on the theory that nobody
     * reads further; the first person to see it asked where the other six days were.
     *
     * <p>The price line (task 39) appears here, at the plan-summary stage, only when something could actually be
     * priced — for a ready-meals week that is every line, straight from the catalog; for a cooking week it is
     * whatever the baseline basket remembers, which on a first week is nothing.
     */
    private String summarise(MealPlan plan, int shoppingListSize, PriceEstimate estimate) {
        WeeklyMealPlan week = MAPPER.convertValue(plan.getPlan(), WeeklyMealPlan.class);
        StringBuilder text = new StringBuilder("План на тиждень готовий.");
        for (DayOfWeek day : DayOfWeek.values()) {
            week.days().stream()
                    .filter(planned -> planned.day() == day)
                    .findFirst()
                    .map(PlannedDay::meals)
                    .filter(meals -> meals != null && !meals.isEmpty())
                    .ifPresent(meals -> text.append('\n')
                            .append(DayLabels.shortLabel(day))
                            .append(": ")
                            .append(meals.stream().map(PlannedMeal::name).collect(Collectors.joining(" / "))));
        }
        String message = text.append("\nСписок покупок: %d позицій.".formatted(shoppingListSize))
                .toString();
        if (estimate.hasPrices()) {
            message += "\n" + shoppingListMessageService.estimateLine(estimate);
        }
        if (plan.getSourceType() == ShoppingListSourceType.READY_MEAL_DIRECT
                && distinctRealProducts(week) < MINIMUM_DISTINCT_READY_MEALS) {
            // Derived from what actually ended up in the plan, not a separate flag from generation — a thin
            // candidate pool and a repetitive week are the same thing in practice.
            message += "\nЧерез твої обмеження знайшлось не так багато готових страв, тому деякі повторюються "
                    + "цього тижня.";
        }
        return message;
    }

    private static long distinctRealProducts(WeeklyMealPlan week) {
        return week.days().stream()
                .flatMap(day -> (day.meals() == null ? List.<PlannedMeal>of() : day.meals()).stream())
                .flatMap(meal ->
                        (meal.ingredients() == null ? List.<PlannedIngredient>of() : meal.ingredients()).stream())
                .map(PlannedIngredient::productId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
    }
}
