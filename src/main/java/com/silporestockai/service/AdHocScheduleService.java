package com.silporestockai.service;

import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.entity.User;
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
 * (task 24), unchanged; this only decides *when*.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdHocScheduleService {

    private final ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;
    private final UserRepository userRepository;
    private final AdHocOrderService adHocOrderService;
    private final TelegramOutboundService telegramOutboundService;

    public void schedule(User user, String themeDescription, Instant triggerAt) {
        scheduledAdHocTaskRepository.save(ScheduledAdHocTask.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .triggerAt(triggerAt)
                .themeDescription(themeDescription)
                .status(ScheduledAdHocTaskStatus.PENDING)
                .createdAt(Instant.now())
                .build());
        // No date here on purpose (see the class javadoc) — but the theme alone, echoed back bare, reads like
        // the bot repeating the person's words with nothing decided. Say what will happen.
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(), "Зроблю це найближчим часом: %s.".formatted(themeDescription));
        log.info("scheduled an ad-hoc purchase for user {} at {}", user.getId(), triggerAt);
    }

    /** Fires every {@code PENDING} task whose trigger time has passed. Returns how many fired. */
    public int sweepDue() {
        List<ScheduledAdHocTask> due = scheduledAdHocTaskRepository.findByStatusAndTriggerAtBefore(
                ScheduledAdHocTaskStatus.PENDING, Instant.now());
        for (ScheduledAdHocTask task : due) {
            userRepository
                    .findById(task.getUserId())
                    .ifPresentOrElse(
                            user -> {
                                adHocOrderService.buildAdHocOrder(
                                        user, task.getThemeDescription(), task.getTriggerAt());
                                task.setStatus(ScheduledAdHocTaskStatus.FIRED);
                                scheduledAdHocTaskRepository.save(task);
                            },
                            () -> log.warn(
                                    "scheduled ad-hoc task {} has no matching user; leaving it pending", task.getId()));
        }
        if (!due.isEmpty()) {
            log.info("fired {} scheduled ad-hoc purchases", due.size());
        }
        return due.size();
    }
}
