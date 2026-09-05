package com.silporestockai.service;

import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.entity.User;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
}
