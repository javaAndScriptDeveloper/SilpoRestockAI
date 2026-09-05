package com.silporestockai.service;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.entity.User;
import com.silporestockai.model.ConversationFlow;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import com.silporestockai.model.TelegramButton;
import com.silporestockai.model.TelegramIncomingUpdate;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * The "Заплановані" view (task 33): every pending {@link ScheduledAdHocTask} the user has, each with its
 * own Редагувати/Скасувати row. A management view, not an intent — see this task's own Notion spec for why
 * that distinction put it behind a menu button instead of {@code IntentRouterService}.
 */
@Slf4j
@Service
public class ScheduledTaskManagementService {

    private static final String PREFIX_EDIT = "sched:edit:";
    private static final String PREFIX_CANCEL = "sched:cancel:";

    public static final String CONTEXT_TASK_ID = "taskId";

    private final ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;
    private final TelegramOutboundService telegramOutboundService;
    private final ClaudeApiClient claudeApiClient;
    private final ConversationStateService conversationStateService;
    private final String editSystemPrompt;

    public ScheduledTaskManagementService(
            ScheduledAdHocTaskRepository scheduledAdHocTaskRepository,
            TelegramOutboundService telegramOutboundService,
            ClaudeApiClient claudeApiClient,
            ConversationStateService conversationStateService,
            @Value("classpath:prompts/scheduled-task-edit-system.txt") Resource editSystemPromptResource) {
        this.scheduledAdHocTaskRepository = scheduledAdHocTaskRepository;
        this.telegramOutboundService = telegramOutboundService;
        this.claudeApiClient = claudeApiClient;
        this.conversationStateService = conversationStateService;
        this.editSystemPrompt = read(editSystemPromptResource);
    }

    public void showPending(User user) {
        List<ScheduledAdHocTask> pending = scheduledAdHocTaskRepository.findByUserIdAndStatusOrderByTriggerAtAsc(
                user.getId(), ScheduledAdHocTaskStatus.PENDING);
        long chatId = user.getTelegramChatId();
        if (pending.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, "Немає запланованих замовлень.");
        }
        for (ScheduledAdHocTask task : pending) {
            telegramOutboundService.sendMessageWithButtons(
                    chatId,
                    task.getThemeDescription(),
                    List.of(
                            TelegramButton.callback("Редагувати", PREFIX_EDIT + task.getId()),
                            TelegramButton.callback("Скасувати", PREFIX_CANCEL + task.getId())));
        }
        showRecentlyFired(user, chatId);
    }

    /**
     * The tail of the view: what already fired. Task 33's spec lists this as optional — it stopped being optional
     * once scheduling started firing on the very next sweep (see {@link AdHocScheduleService}): a task is PENDING
     * for at most fifteen minutes, so a view of pending tasks alone is empty almost every time anyone opens it,
     * and "Немає запланованих замовлень" right after «замов вино» reads as "I lost your request". Naming the
     * last few that ran is the reassurance the button exists for.
     */
    private void showRecentlyFired(User user, long chatId) {
        List<ScheduledAdHocTask> fired = scheduledAdHocTaskRepository.findTop5ByUserIdAndStatusOrderByCreatedAtDesc(
                user.getId(), ScheduledAdHocTaskStatus.FIRED);
        if (fired.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder("Нещодавно виконав:");
        fired.forEach(task -> text.append("\n✅ ").append(task.getThemeDescription()));
        telegramOutboundService.sendMessage(chatId, text.toString());
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
            telegramOutboundService.sendMessage(
                    chatId, "Скасовано: " + task.get().getThemeDescription() + ".");
            return;
        }
        if (tap.data().startsWith(PREFIX_EDIT)) {
            UUID taskId = UUID.fromString(tap.data().substring(PREFIX_EDIT.length()));
            Optional<ScheduledAdHocTask> task = pendingTaskOwnedBy(user, taskId);
            if (task.isEmpty()) {
                telegramOutboundService.sendMessage(chatId, "Це замовлення вже неактуальне.");
                return;
            }
            conversationStateService.save(
                    chatId, ConversationFlow.SCHEDULED_TASK_EDIT, null, Map.of(CONTEXT_TASK_ID, taskId.toString()));
            telegramOutboundService.sendMessage(chatId, "Напиши нову дату/час і/або нову тему для цього замовлення.");
            return;
        }
        log.debug("ignoring unrecognised scheduled-task callback {} for user {}", tap.data(), user.getId());
    }

    public void handleEditReply(User user, TelegramIncomingUpdate incoming) {
        long chatId = user.getTelegramChatId();
        if (!(incoming instanceof TelegramIncomingUpdate.Text text)) {
            telegramOutboundService.sendMessage(chatId, "Напиши, будь ласка, текстом.");
            return;
        }
        Object rawTaskId = conversationStateService.load(chatId).getContext().get(CONTEXT_TASK_ID);
        UUID taskId = UUID.fromString(String.valueOf(rawTaskId));
        Optional<ScheduledAdHocTask> maybeTask = pendingTaskOwnedBy(user, taskId);
        if (maybeTask.isEmpty()) {
            conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
            telegramOutboundService.sendMessage(chatId, "Це замовлення вже неактуальне.");
            return;
        }
        EditSlots slots;
        try {
            slots = claudeApiClient.completeStructured(editSystemPrompt, text.text(), EditSlots.class);
        } catch (RuntimeException e) {
            log.warn("could not extract scheduled-task edit slots for chat {}", chatId, e);
            telegramOutboundService.sendMessage(
                    chatId, "Не зрозумів. Напиши, будь ласка, ще раз — нову дату/час або тему.");
            return;
        }
        boolean hasTheme =
                slots.themeDescription() != null && !slots.themeDescription().isBlank();
        Instant newTriggerAt = parseIsoOrNull(slots.targetDateTimeIso());
        if (!hasTheme && newTriggerAt == null) {
            telegramOutboundService.sendMessage(
                    chatId, "Не зрозумів. Напиши, будь ласка, ще раз — нову дату/час або тему.");
            return;
        }
        ScheduledAdHocTask task = maybeTask.get();
        if (hasTheme) {
            task.setThemeDescription(slots.themeDescription());
        }
        if (newTriggerAt != null) {
            task.setTriggerAt(newTriggerAt);
        }
        scheduledAdHocTaskRepository.save(task);
        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        telegramOutboundService.sendMessage(chatId, "Оновлено: " + task.getThemeDescription() + ".");
    }

    /** {@code Optional.empty()} covers both "no such task" and "not PENDING any more" — both mean the same thing to the user. */
    private Optional<ScheduledAdHocTask> pendingTaskOwnedBy(User user, UUID taskId) {
        return scheduledAdHocTaskRepository
                .findById(taskId)
                .filter(task -> task.getUserId().equals(user.getId()))
                .filter(task -> task.getStatus() == ScheduledAdHocTaskStatus.PENDING);
    }

    /** A parse failure here means "no time change," unlike a brand-new schedule's own +1-hour fallback. */
    private static Instant parseIsoOrNull(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the scheduled-task-edit system prompt", e);
        }
    }

    private record EditSlots(String themeDescription, String targetDateTimeIso) {}
}
