package com.silporestockai.service;

import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.entity.User;
import com.silporestockai.model.OrderTrigger;
import com.silporestockai.model.ScheduledAdHocTaskKind;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "Закажи до п'ятниці..." names a deadline, not a desired start time — waiting until near it only delays a
 * purchase that could happen right away. {@link IntentRouterService#route} always passes {@link Instant#now()}
 * as {@code triggerAt}, so this fires on the very next sweep; the deadline itself is never enforced here
 * because "as soon as possible" is always at or before it. See docs/OVERNIGHT_QUESTIONS.md for the live-test
 * report that caught the earlier, literal reading. Firing the order itself is {@link AdHocOrderService}'s job
 * (task 24) or, for a dish, {@link DishIngredientsService}'s (task 36); this only decides *when* and *which*.
 *
 * <p>{@link #fire} is the one place a task turns into an order, whether the sweep found it
 * due or {@link #scheduleDishIngredients} fired it the moment it was written. "Execute now" is not a bypass —
 * it is the same row and the same method with zero delay.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdHocScheduleService {

    private final ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;
    private final UserRepository userRepository;
    private final AdHocOrderService adHocOrderService;
    private final DishIngredientsService dishIngredientsService;
    private final TelegramOutboundService telegramOutboundService;

    public void schedule(User user, String themeDescription, Instant triggerAt) {
        scheduledAdHocTaskRepository.save(ScheduledAdHocTask.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .triggerAt(triggerAt)
                .themeDescription(themeDescription)
                .kind(ScheduledAdHocTaskKind.SNACK_THEME)
                .status(ScheduledAdHocTaskStatus.PENDING)
                .createdAt(Instant.now())
                .build());
        // No date here on purpose (see the class javadoc) — but the theme alone, echoed back bare, reads like
        // the bot repeating the person's words with nothing decided. Say what will happen.
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(), "Зроблю це найближчим часом: %s.".formatted(themeDescription));
        log.info("scheduled an ad-hoc purchase for user {} at {}", user.getId(), triggerAt);
    }

    /**
     * A dish-ingredients order (task 36): the same row every one-off purchase gets, then fired at once through
     * the same {@link #fire} the sweep uses. The person asked for it now; a fifteen-minute cron between "замов усе
     * для карбонари" and the cart is a wait nobody asked for, and the row still exists — «Заплановані» shows it
     * under «Нещодавно виконав».
     */
    public void scheduleDishIngredients(User user, String dishName, OrderTrigger trigger) {
        ScheduledAdHocTask task = scheduledAdHocTaskRepository.save(ScheduledAdHocTask.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .triggerAt(Instant.now())
                .themeDescription(dishName)
                .kind(ScheduledAdHocTaskKind.DISH_INGREDIENTS)
                .status(ScheduledAdHocTaskStatus.PENDING)
                .createdAt(Instant.now())
                .build());
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(), "Зберу все для «%s» — секунду.".formatted(dishName));
        log.info("scheduled a dish-ingredients order for «{}» for user {}, firing now", dishName, user.getId());
        fire(task, user, trigger);
    }

    /** Fires every {@code PENDING} task whose trigger time has passed. Returns how many fired. */
    public int sweepDue() {
        List<ScheduledAdHocTask> due = scheduledAdHocTaskRepository.findByStatusAndTriggerAtBefore(
                ScheduledAdHocTaskStatus.PENDING, Instant.now());
        for (ScheduledAdHocTask task : due) {
            userRepository
                    .findById(task.getUserId())
                    .ifPresentOrElse(
                            // A deadline the person chose is not latency: the clock starts at the sweep.
                            user -> fire(task, user, OrderTrigger.of("AD_HOC_SCHEDULED_PURCHASE", Instant.now())),
                            () -> log.warn(
                                    "scheduled ad-hoc task {} has no matching user; leaving it pending", task.getId()));
        }
        if (!due.isEmpty()) {
            log.info("fired {} scheduled ad-hoc purchases", due.size());
        }
        return due.size();
    }

    /** Turns one task into one order, by kind, and marks it fired. */
    private void fire(ScheduledAdHocTask task, User user, OrderTrigger trigger) {
        // Claimed before the work, not after it. A dish order fires the moment it is scheduled and takes a model
        // call plus a cart build; live, a sweep tick landed inside that window, found the row still PENDING and
        // fired it a second time — two carts, two «Не зрозумів» — for one sentence. A task whose work fails goes
        // back to PENDING so the next sweep retries it, which is what the status meant all along.
        task.setStatus(ScheduledAdHocTaskStatus.FIRED);
        scheduledAdHocTaskRepository.save(task);
        try {
            switch (task.getKind() == null ? ScheduledAdHocTaskKind.SNACK_THEME : task.getKind()) {
                case SNACK_THEME ->
                    adHocOrderService.buildAdHocOrder(user, task.getThemeDescription(), task.getTriggerAt(), trigger);
                case DISH_INGREDIENTS ->
                    dishIngredientsService.orderIngredients(user, task.getThemeDescription(), trigger);
            }
        } catch (RuntimeException e) {
            task.setStatus(ScheduledAdHocTaskStatus.PENDING);
            scheduledAdHocTaskRepository.save(task);
            throw e;
        }
    }
}
