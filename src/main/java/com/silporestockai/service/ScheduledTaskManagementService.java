package com.silporestockai.service;

import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.entity.User;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The "Заплановані" view (task 33): every pending {@link ScheduledAdHocTask} the user has, each with its
 * own Редагувати/Скасувати row. A management view, not an intent — see this task's own Notion spec for why
 * that distinction put it behind a menu button instead of {@code IntentRouterService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTaskManagementService {

    // Locale.forLanguageTag("uk") explicitly — see AdHocScheduleService's identical formatter for why the
    // JVM default locale cannot be trusted here.
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("uk"))
            .withZone(ZoneId.of("Europe/Kyiv"));

    private static final String PREFIX_EDIT = "sched:edit:";
    private static final String PREFIX_CANCEL = "sched:cancel:";

    private final ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;
    private final TelegramOutboundService telegramOutboundService;

    public void showPending(User user) {
        List<ScheduledAdHocTask> pending = scheduledAdHocTaskRepository.findByUserIdAndStatusOrderByTriggerAtAsc(
                user.getId(), ScheduledAdHocTaskStatus.PENDING);
        long chatId = user.getTelegramChatId();
        if (pending.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, "Немає запланованих замовлень.");
            return;
        }
        for (ScheduledAdHocTask task : pending) {
            telegramOutboundService.sendMessageWithButtons(
                    chatId,
                    "%s — заплановано на %s"
                            .formatted(task.getThemeDescription(), DISPLAY.format(task.getTriggerAt())),
                    List.of(
                            TelegramButton.callback("Редагувати", "sched:edit:" + task.getId()),
                            TelegramButton.callback("Скасувати", "sched:cancel:" + task.getId())));
        }
    }

    public void handleButtonTap(User user, TelegramIncomingUpdate.ButtonTap tap) {
        telegramOutboundService.answerCallback(tap.callbackQueryId());
        long chatId = user.getTelegramChatId();
        if (tap.data().startsWith(PREFIX_CANCEL)) {
            UUID taskId = UUID.fromString(tap.data().substring(PREFIX_CANCEL.length()));
            Optional<ScheduledAdHocTask> task = pendingTaskOwnedBy(user, taskId);
            if (task.isEmpty()) {
                telegramOutboundService.sendMessage(chatId, "Це замовлення вже неактуальне.");
                return;
            }
            task.get().setStatus(ScheduledAdHocTaskStatus.CANCELLED);
            scheduledAdHocTaskRepository.save(task.get());
            telegramOutboundService.sendMessage(chatId, "Скасовано: " + task.get().getThemeDescription() + ".");
            return;
        }
        log.debug("ignoring unrecognised scheduled-task callback {} for user {}", tap.data(), user.getId());
    }

    /** {@code Optional.empty()} covers both "no such task" and "not PENDING any more" — both mean the same thing to the user. */
    private Optional<ScheduledAdHocTask> pendingTaskOwnedBy(User user, UUID taskId) {
        return scheduledAdHocTaskRepository
                .findById(taskId)
                .filter(task -> task.getUserId().equals(user.getId()))
                .filter(task -> task.getStatus() == ScheduledAdHocTaskStatus.PENDING);
    }
}
