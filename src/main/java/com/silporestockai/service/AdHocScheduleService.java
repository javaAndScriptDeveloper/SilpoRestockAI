package com.silporestockai.service;

import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.entity.User;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "Закажи до п'ятниці..." is a future promise, not an immediate order — this is where that promise waits.
 * Firing it is {@link AdHocOrderService}'s job (task 24), unchanged; this only decides *when*.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdHocScheduleService {

    // Locale.forLanguageTag("uk") explicitly, not the JVM default — this process runs with
    // -Duser.language=en, which silently rendered "September" instead of "вересня" in a user-facing message.
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("uk"))
            .withZone(ZoneId.of("Europe/Kyiv"));

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
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                "Заплановано на %s: %s.".formatted(DISPLAY.format(triggerAt), themeDescription));
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
