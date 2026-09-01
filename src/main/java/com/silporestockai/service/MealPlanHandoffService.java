package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.OnboardingCompletedEvent;
import com.silporestockai.model.PlannedDay;
import com.silporestockai.model.PlannedMeal;
import com.silporestockai.model.WeeklyMealPlan;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MealPlanService mealPlanService;
    private final ShoppingListService shoppingListService;
    private final CartConfirmationService cartConfirmationService;
    private final UserRepository userRepository;
    private final TelegramOutboundService telegramOutboundService;

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

    /** Generates, stores and announces the first weekly plan. Runs on the caller's thread. */
    public void generateFirstPlan(UUID userId) {
        userRepository
                .findById(userId)
                .ifPresentOrElse(
                        user -> {
                            try {
                                MealPlan plan = mealPlanService.generateWeeklyPlan(userId);
                                List<ShoppingListItem> list = shoppingListService.deriveFromMealPlan(plan.getId());
                                telegramOutboundService.sendMessage(
                                        user.getTelegramChatId(), summarise(plan, list.size()));
                                // The list is only half the answer: flow #1 ends at a cart the user confirmed,
                                // and present() reports its own failures rather than throwing.
                                cartConfirmationService.present(user, list);
                            } catch (RuntimeException e) {
                                log.error("could not generate the first plan for user {}", userId, e);
                                telegramOutboundService.sendMessage(
                                        user.getTelegramChatId(),
                                        "План скласти не вдалось. Спробую ще раз трохи пізніше.");
                            }
                        },
                        () -> log.warn("onboarding completed for unknown user {}", userId));
    }

    /** One line: the week is ready, and here is Monday, which is the only part anyone reads immediately. */
    private static String summarise(MealPlan plan, int shoppingListSize) {
        WeeklyMealPlan week = MAPPER.convertValue(plan.getPlan(), WeeklyMealPlan.class);
        String monday = week.days().stream()
                .filter(day -> day.day() == DayOfWeek.MONDAY)
                .findFirst()
                .map(PlannedDay::meals)
                .orElse(List.of())
                .stream()
                .map(PlannedMeal::name)
                .collect(Collectors.joining(" / "));
        return "План на тиждень готовий, %d днів.\nПонеділок: %s\nСписок покупок: %d позицій."
                .formatted(week.days().size(), monday, shoppingListSize);
    }
}
