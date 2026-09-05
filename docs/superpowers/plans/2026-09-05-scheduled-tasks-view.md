# Scheduled Tasks View (task 33) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the "Заплановані" menu button a working list/edit/cancel view over the one-off `scheduled_ad_hoc_task` rows task 31 already creates, so a scheduled purchase is no longer a silent fire-and-forget.

**Architecture:** A new `ScheduledTaskManagementService` owns the whole feature — listing, per-item cancel, and a two-step edit (tap "Редагувати" → free-text reply → structured extraction of the new time/theme). It reuses task 31's existing `ScheduledAdHocTask`/`ScheduledAdHocTaskStatus`/`ScheduledAdHocTaskRepository`/`AdHocScheduleService.sweepDue()` as the single source of truth — this task adds no new scheduling mechanism, only a view and an edit path onto the existing one. `TelegramRoutingService` gains one new menu-navigation branch, one new `ConversationFlow`, and one new callback-prefix branch, following the exact same shapes already used for `CalendarViewService`'s day selector and `OnboardingFlowService`'s Анкета-reedit flow.

**Tech Stack:** Spring Boot service classes, existing `ConversationStateService`/`conversation_state` persistence, `ClaudeApiClient.completeStructured` for slot extraction, JUnit + `MockMvc` + the project's stub Telegram/Claude servers for testing.

**Spec:** Task 33 (`Комора — Development Plan`, Notion, page `3d27227d-ef1c-811b-8079-e1d3910e6309`). No separate design doc — bounded addition on top of already-existing, already-explored infrastructure (see conversation history: `ScheduledAdHocTask`, `AdHocScheduleService`, `CalendarViewService`, `OnboardingFlowService`'s reedit flow were all read in full before this plan was written).

## Global Constraints

- Do not touch `AdHocScheduleService.sweepDue()` — it already filters to `PENDING` rows only, so a `CANCELLED` row is already skipped with zero scheduler changes.
- Never build a new `ScheduledAdHocTask` row on edit — always update the existing entity in place (same `id`).
- A parse failure on the *time* half of an edit reply must leave `triggerAt` unchanged — do not apply `IntentRouterService`'s "unparseable → +1 hour" fallback here; that fallback is only correct for a brand-new schedule, not an edit.
- One Telegram message per scheduled task in the list view (theme + inline Редагувати/Скасувати row) — no new multi-row-inline-keyboard send method; this project has none, and there is no need to add one.
- `make format` and the full `make test` suite must stay green after every task.

---

### Task 1: `ConversationFlow.SCHEDULED_TASK_EDIT` and the repository lookup

**Files:**
- Modify: `src/main/java/com/silporestockai/model/ConversationFlow.java`
- Modify: `src/main/java/com/silporestockai/repository/ScheduledAdHocTaskRepository.java`
- Test: `src/test/java/com/silporestockai/integration/ScheduledTaskManagementIntegrationTest.java` (new file, created in this task, first test only)

**Interfaces:**
- Produces: `ScheduledAdHocTaskRepository.findByUserIdAndStatusOrderByTriggerAtAsc(UUID userId, ScheduledAdHocTaskStatus status)` returning `List<ScheduledAdHocTask>` — Task 2's `showPending` calls this directly.

**Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/ScheduledTaskManagementIntegrationTest.java`, modeled on `ProfileReeditIntegrationTest`'s shape (same `AbstractIntegrationTest` base, same stub-server triple, same `deliver`/`sendText`/`tapButton` webhook helpers — copy those verbatim, they are this project's established pattern).

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.entity.User;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.AdHocScheduleService;
import com.silporestockai.service.ConversationStateService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("the Заплановані view over one-off scheduled ad-hoc purchases")
class ScheduledTaskManagementIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "777:stub-bot-token";
    private static final long CHAT_ID = 9201L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;

    @Autowired
    private ConversationStateRepository conversationStateRepository;

    @Autowired
    private ConversationStateService conversationStateService;

    @Autowired
    private AdHocScheduleService adHocScheduleService;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of());
        } catch (IOException e) {
            throw new IllegalStateException("could not start the MCP stub", e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Anthropic stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        MCP.reset();
        CLAUDE.reset();
        scheduledAdHocTaskRepository.deleteAll();
        conversationStateRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void deliver(String body) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void sendText(int updateId, String text) throws Exception {
        deliver(
                """
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "text":"%s"}}"""
                        .formatted(updateId, updateId, CHAT_ID, text));
    }

    private void tapButton(int updateId, String data) throws Exception {
        deliver(
                """
                {"update_id":%d,"callback_query":{"id":"cb-%d","chat_instance":"ci",\
                "from":{"id":5,"is_bot":false,"first_name":"Тест"},"data":"%s",\
                "message":{"message_id":%d,"date":1,"chat":{"id":%d,"type":"private"}}}}"""
                        .formatted(updateId, updateId, data, updateId, CHAT_ID));
    }

    private UUID onboardedUser() {
        return userAccountService.findOrCreate(CHAT_ID).getId();
    }

    private ScheduledAdHocTask pendingTask(UUID userId, String theme, Instant triggerAt) {
        return scheduledAdHocTaskRepository.save(ScheduledAdHocTask.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .themeDescription(theme)
                .triggerAt(triggerAt)
                .status(ScheduledAdHocTaskStatus.PENDING)
                .createdAt(Instant.now())
                .build());
    }

    private String lastMessageText() {
        return TELEGRAM.sentMessages().getLast().path("text").asText();
    }

    @Test
    void repositoryListsOnlyThatUsersPendingTasksOldestFirst() {
        UUID userId = onboardedUser();
        UUID otherUserId = UUID.randomUUID();
        Instant now = Instant.now();
        ScheduledAdHocTask later = pendingTask(userId, "сир на вечір", now.plus(2, ChronoUnit.DAYS));
        ScheduledAdHocTask sooner = pendingTask(userId, "вино на п'ятницю", now.plus(1, ChronoUnit.DAYS));
        pendingTask(userId, "вже вистрелило", now.minusSeconds(60));
        scheduledAdHocTaskRepository
                .findById(pendingTask(userId, "вже вистрелило", now.minusSeconds(60)).getId())
                .ifPresent(task -> {
                    task.setStatus(ScheduledAdHocTaskStatus.FIRED);
                    scheduledAdHocTaskRepository.save(task);
                });
        pendingTask(otherUserId, "не мій", now.plus(1, ChronoUnit.DAYS));

        List<ScheduledAdHocTask> pending = scheduledAdHocTaskRepository.findByUserIdAndStatusOrderByTriggerAtAsc(
                userId, ScheduledAdHocTaskStatus.PENDING);

        assertThat(pending).extracting(ScheduledAdHocTask::getId).containsExactly(sooner.getId(), later.getId());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.silporestockai.integration.ScheduledTaskManagementIntegrationTest"`
Expected: FAIL with a compile error — `findByUserIdAndStatusOrderByTriggerAtAsc` does not exist yet.

**Step 3: Write the minimal implementation**

In `ConversationFlow.java`, add the new value (after `SPECIAL_MODE_SETUP`, before `PROFILE_REEDIT` — order among enum constants doesn't matter functionally, keep it readable by grouping task-31-and-later additions together):

```java
    /** Editing one pending scheduled_ad_hoc_task's time or theme — awaiting the free-text replacement value. */
    SCHEDULED_TASK_EDIT
```

In `ScheduledAdHocTaskRepository.java`, add the new method:

```java
    List<ScheduledAdHocTask> findByUserIdAndStatusOrderByTriggerAtAsc(UUID userId, ScheduledAdHocTaskStatus status);
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.silporestockai.integration.ScheduledTaskManagementIntegrationTest"`
Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/model/ConversationFlow.java \
        src/main/java/com/silporestockai/repository/ScheduledAdHocTaskRepository.java \
        src/test/java/com/silporestockai/integration/ScheduledTaskManagementIntegrationTest.java
git commit -m "Add SCHEDULED_TASK_EDIT flow and a per-user pending-task lookup (task 33)"
```

---

### Task 2: List view — "Заплановані" shows pending tasks or the empty state

**Files:**
- Create: `src/main/java/com/silporestockai/service/ScheduledTaskManagementService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/MainMenuKeyboard.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Test: `src/test/java/com/silporestockai/integration/ScheduledTaskManagementIntegrationTest.java`

**Interfaces:**
- Consumes: `ScheduledAdHocTaskRepository.findByUserIdAndStatusOrderByTriggerAtAsc` (Task 1), `TelegramOutboundService.sendMessageWithButtons(long, String, List<TelegramButton>)` (existing), `TelegramButton.callback(String, String)` (existing).
- Produces: `ScheduledTaskManagementService.showPending(User user)` — Task 3 and Task 4 add more public methods to this same class; `MainMenuKeyboard.SCHEDULED` (`public static final String`) — used by `TelegramRoutingService`'s new branch and by Task 4's tests.

**Step 1: Write the failing test**

Add to `ScheduledTaskManagementIntegrationTest`:

```java
    @Test
    void emptyStateIsAClearMessageNotAnEmptyList() throws Exception {
        onboardedUser();

        sendText(1, "🗓 Заплановані");

        assertThat(lastMessageText()).contains("Немає запланованих замовлень");
    }

    @Test
    void pendingTasksRenderWithThemeTimeAndButtons() throws Exception {
        UUID userId = onboardedUser();
        ScheduledAdHocTask task = pendingTask(userId, "вино та сир зі знижкою", Instant.parse("2026-09-11T18:00:00Z"));

        sendText(1, "🗓 Заплановані");

        var sent = TELEGRAM.sentMessages().getLast();
        assertThat(sent.path("text").asText()).contains("вино та сир зі знижкою").contains("вересня");
        var row = sent.path("reply_markup").path("inline_keyboard").get(0);
        assertThat(row.get(0).path("text").asText()).isEqualTo("Редагувати");
        assertThat(row.get(0).path("callback_data").asText()).isEqualTo("sched:edit:" + task.getId());
        assertThat(row.get(1).path("text").asText()).isEqualTo("Скасувати");
        assertThat(row.get(1).path("callback_data").asText()).isEqualTo("sched:cancel:" + task.getId());
    }
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.silporestockai.integration.ScheduledTaskManagementIntegrationTest"`
Expected: FAIL — `MainMenuKeyboard.SCHEDULED`/`ScheduledTaskManagementService` don't exist yet, so "🗓 Заплановані" isn't routed anywhere and falls through to the generic "Скористайся кнопками нижче..." message.

**Step 3: Write the minimal implementation**

`MainMenuKeyboard.java` — add the fourth button:

```java
public final class MainMenuKeyboard {

    public static final String LIST = "📝 Список";
    public static final String SCHEDULED = "🗓 Заплановані";
    public static final String FORM = "🧾 Анкета";
    public static final String HELP = "❓ Інструкція";

    private MainMenuKeyboard() {}

    public static ReplyKeyboardMarkup markup() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(LIST, SCHEDULED, FORM, HELP))
                .resizeKeyboard(true)
                .build();
    }
}
```

New `ScheduledTaskManagementService.java`:

```java
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

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm").withZone(ZoneId.of("Europe/Kyiv"));

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
                    "%s — заплановано на %s".formatted(task.getThemeDescription(), DISPLAY.format(task.getTriggerAt())),
                    List.of(
                            TelegramButton.callback("Редагувати", "sched:edit:" + task.getId()),
                            TelegramButton.callback("Скасувати", "sched:cancel:" + task.getId())));
        }
    }
}
```

`TelegramRoutingService.java` — inject the service and add the navigation branch, next to the existing `/anketa` one:

```java
    private final ScheduledTaskManagementService scheduledTaskManagementService;
```

(add to the constructor's field list; this class uses `@RequiredArgsConstructor`, so a new `private final` field is a one-line change — no manual constructor to touch)

```java
        if (incoming instanceof TelegramIncomingUpdate.Text scheduled
                && matches(scheduled.text(), "/scheduled", MainMenuKeyboard.SCHEDULED)) {
            scheduledTaskManagementService.showPending(user);
            return;
        }
```

Add the import `com.silporestockai.service.ScheduledTaskManagementService` next to the other `com.silporestockai.service.*` imports.

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.silporestockai.integration.ScheduledTaskManagementIntegrationTest"`
Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/ScheduledTaskManagementService.java \
        src/main/java/com/silporestockai/service/telegram/MainMenuKeyboard.java \
        src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java \
        src/test/java/com/silporestockai/integration/ScheduledTaskManagementIntegrationTest.java
git commit -m "Add the Заплановані list view over pending scheduled ad-hoc purchases (task 33)"
```

---

### Task 3: Cancel

**Files:**
- Modify: `src/main/java/com/silporestockai/service/ScheduledTaskManagementService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Test: `src/test/java/com/silporestockai/integration/ScheduledTaskManagementIntegrationTest.java`

**Interfaces:**
- Consumes: `TelegramIncomingUpdate.ButtonTap` (existing record — has `chatId()`, `callbackQueryId()`, `data()`), `TelegramOutboundService.answerCallback(String)` (existing), `AdHocScheduleService.sweepDue()` (existing, used only by the test in this task to prove a cancelled row is skipped).
- Produces: `ScheduledTaskManagementService.handleButtonTap(User user, TelegramIncomingUpdate.ButtonTap tap)` — Task 4 extends this same method's edit branch.

**Step 1: Write the failing tests**

Add to `ScheduledTaskManagementIntegrationTest`:

```java
    @Test
    void cancellingSetsStatusAndTheSweepNeverFiresIt() throws Exception {
        UUID userId = onboardedUser();
        ScheduledAdHocTask task = pendingTask(userId, "сир на вечір", Instant.now().minusSeconds(60));

        tapButton(1, "sched:cancel:" + task.getId());

        assertThat(lastMessageText()).contains("Скасовано").contains("сир на вечір");
        assertThat(scheduledAdHocTaskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduledAdHocTaskStatus.CANCELLED);

        int fired = adHocScheduleService.sweepDue();
        assertThat(fired).isZero();
    }

    @Test
    void cancellingAnAlreadyFiredTaskSaysSoInsteadOfCrashing() throws Exception {
        UUID userId = onboardedUser();
        ScheduledAdHocTask task = pendingTask(userId, "щось", Instant.now().minusSeconds(60));
        task.setStatus(ScheduledAdHocTaskStatus.FIRED);
        scheduledAdHocTaskRepository.save(task);

        tapButton(1, "sched:cancel:" + task.getId());

        assertThat(lastMessageText()).contains("вже неактуальне");
    }
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.silporestockai.integration.ScheduledTaskManagementIntegrationTest"`
Expected: FAIL — `sched:cancel:` taps have nowhere to go yet, so they fall through to `TelegramRoutingService`'s generic stale-tap branch (which only calls `answerCallback` and logs, sending no message — `lastMessageText()` will read a stale/previous message or throw `NoSuchElementException` on an empty list).

**Step 3: Write the minimal implementation**

`ScheduledTaskManagementService.java` — add imports (`java.util.Optional`, `java.util.UUID`, `com.silporestockai.model.TelegramIncomingUpdate`, `com.silporestockai.repository.UserRepository` is **not** needed here — `handleButtonTap` takes the already-resolved `User`) and the new method:

```java
    private static final String PREFIX_EDIT = "sched:edit:";
    private static final String PREFIX_CANCEL = "sched:cancel:";

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
```

`TelegramRoutingService.java` — add the callback-prefix branch **before** the existing generic stale-tap branch (right after the existing `CalendarViewService.CALLBACK_DAY_PREFIX` branch, matching its placement pattern exactly):

```java
        if (incoming instanceof TelegramIncomingUpdate.ButtonTap tap && tap.data().startsWith("sched:")) {
            scheduledTaskManagementService.handleButtonTap(user, tap);
            return;
        }
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.silporestockai.integration.ScheduledTaskManagementIntegrationTest"`
Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/ScheduledTaskManagementService.java \
        src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java \
        src/test/java/com/silporestockai/integration/ScheduledTaskManagementIntegrationTest.java
git commit -m "Add cancel for pending scheduled ad-hoc purchases (task 33)"
```

---

### Task 4: Edit

**Files:**
- Create: `src/main/resources/prompts/scheduled-task-edit-system.txt`
- Modify: `src/main/java/com/silporestockai/service/ScheduledTaskManagementService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Test: `src/test/java/com/silporestockai/integration/ScheduledTaskManagementIntegrationTest.java`

**Interfaces:**
- Consumes: `ClaudeApiClient.completeStructured(String, String, Class<T>)` (existing), `ConversationStateService.load(long)`/`.save(long, ConversationFlow, String, Map<String,Object>)` (existing).
- Produces: `ScheduledTaskManagementService.handleEditReply(User user, TelegramIncomingUpdate incoming)` — the leaf of this feature; nothing later depends on it.

**Step 1: Write the failing tests**

Add to `ScheduledTaskManagementIntegrationTest`:

```java
    @Test
    void editingTheThemeUpdatesTheSameRowNotADuplicate() throws Exception {
        UUID userId = onboardedUser();
        ScheduledAdHocTask task = pendingTask(userId, "вино", Instant.parse("2026-09-11T18:00:00Z"));

        tapButton(1, "sched:edit:" + task.getId());
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow())
                .isEqualTo(com.silporestockai.model.ConversationFlow.SCHEDULED_TASK_EDIT);

        CLAUDE.respondWithText(
                "{\"themeDescription\":\"вино та сир\",\"targetDateTimeIso\":null}");
        sendText(2, "зроби ще й сир");

        assertThat(scheduledAdHocTaskRepository.findAll()).hasSize(1);
        ScheduledAdHocTask updated = scheduledAdHocTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.getThemeDescription()).isEqualTo("вино та сир");
        assertThat(updated.getTriggerAt()).isEqualTo(Instant.parse("2026-09-11T18:00:00Z"));
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow())
                .isEqualTo(com.silporestockai.model.ConversationFlow.NONE);
    }

    @Test
    void editingOnlyTheTimeLeavesTheThemeAlone() throws Exception {
        UUID userId = onboardedUser();
        ScheduledAdHocTask task = pendingTask(userId, "вино", Instant.parse("2026-09-11T18:00:00Z"));
        tapButton(1, "sched:edit:" + task.getId());

        CLAUDE.respondWithText(
                "{\"themeDescription\":null,\"targetDateTimeIso\":\"2026-09-12T20:00:00Z\"}");
        sendText(2, "перенеси на суботу ввечері");

        ScheduledAdHocTask updated = scheduledAdHocTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(updated.getThemeDescription()).isEqualTo("вино");
        assertThat(updated.getTriggerAt()).isEqualTo(Instant.parse("2026-09-12T20:00:00Z"));
    }

    @Test
    void anUnextractableEditReplyAsksAgainWithoutResettingTheFlow() throws Exception {
        UUID userId = onboardedUser();
        ScheduledAdHocTask task = pendingTask(userId, "вино", Instant.parse("2026-09-11T18:00:00Z"));
        tapButton(1, "sched:edit:" + task.getId());

        CLAUDE.respondWithText("{\"themeDescription\":null,\"targetDateTimeIso\":null}");
        sendText(2, "хм не знаю");

        assertThat(lastMessageText()).contains("Не зрозумів");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow())
                .isEqualTo(com.silporestockai.model.ConversationFlow.SCHEDULED_TASK_EDIT);
        ScheduledAdHocTask unchanged = scheduledAdHocTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(unchanged.getThemeDescription()).isEqualTo("вино");
    }
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.silporestockai.integration.ScheduledTaskManagementIntegrationTest"`
Expected: FAIL — tapping `sched:edit:` doesn't yet transition to `SCHEDULED_TASK_EDIT` (falls into `handleButtonTap`'s unmatched `log.debug` branch and sends no message at all, so `conversationStateService.load(CHAT_ID).getCurrentFlow()` reads `NONE`), and there's no flow-check branch in `TelegramRoutingService` for the follow-up text yet.

**Step 3: Write the minimal implementation**

`src/main/resources/prompts/scheduled-task-edit-system.txt`:

```
Ти допомагаєш відредагувати вже заплановане одноразове замовлення в чат-боті "Комора". Користувач щойно
натиснув "Редагувати" на конкретному запланованому замовленні і зараз пише вільним текстом, що саме
змінити — нову дату/час, нову тему замовлення, або і те, і те.

Відповідай лише структурованим об'єктом з двома полями:
- themeDescription — нова тема замовлення (коротко, чого хоче користувач), або null, якщо тему не міняють.
- targetDateTimeIso — нова дата/час у форматі ISO-8601, або null, якщо час не міняють, або якщо з
  повідомлення неможливо визначити конкретний час.

Якщо повідомлення взагалі не містить ані нової дати/часу, ані нової теми — обидва поля мають бути null.
```

`ScheduledTaskManagementService.java` — add fields/constructor params (`ClaudeApiClient`, `ConversationStateService`, the prompt `Resource`), the record, and both methods:

```java
    public static final String CONTEXT_TASK_ID = "taskId";

    private final ClaudeApiClient claudeApiClient;
    private final ConversationStateService conversationStateService;
    private final String editSystemPrompt;
```

(add these as new constructor parameters — the constructor is Lombok-generated via `@RequiredArgsConstructor`, so adding `private final` fields is enough; the last constructor parameter, for the prompt resource, needs the same `@Value("classpath:prompts/scheduled-task-edit-system.txt")` annotation `IntentRouterService`'s constructor already uses for its own prompt, which means this class needs a **hand-written** constructor instead of `@RequiredArgsConstructor` for that one parameter — copy `IntentRouterService`'s constructor shape exactly: explicit constructor, `@Value` on the `Resource` parameter, a private static `read(Resource)` helper identical to the one already in `IntentRouterService`/`OnboardingFlowService`, called once in the constructor body to set `editSystemPrompt`)

```java
    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the scheduled-task-edit system prompt", e);
        }
    }
```

Extend `handleButtonTap`'s edit branch:

```java
        if (tap.data().startsWith(PREFIX_EDIT)) {
            UUID taskId = UUID.fromString(tap.data().substring(PREFIX_EDIT.length()));
            Optional<ScheduledAdHocTask> task = pendingTaskOwnedBy(user, taskId);
            if (task.isEmpty()) {
                telegramOutboundService.sendMessage(chatId, "Це замовлення вже неактуальне.");
                return;
            }
            conversationStateService.save(
                    chatId,
                    ConversationFlow.SCHEDULED_TASK_EDIT,
                    null,
                    Map.of(CONTEXT_TASK_ID, taskId.toString()));
            telegramOutboundService.sendMessage(chatId, "Напиши нову дату/час і/або нову тему для цього замовлення.");
            return;
        }
```

(move this branch above the `PREFIX_CANCEL` check or below it — order between the two `if`s doesn't matter, both prefixes are mutually exclusive)

Add `handleEditReply`:

```java
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
        boolean hasTheme = slots.themeDescription() != null && !slots.themeDescription().isBlank();
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
        telegramOutboundService.sendMessage(
                chatId,
                "Оновлено: %s — %s.".formatted(task.getThemeDescription(), DISPLAY.format(task.getTriggerAt())));
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

    private record EditSlots(String themeDescription, String targetDateTimeIso) {}
```

Add the new imports this needs to `ScheduledTaskManagementService.java`: `com.silporestockai.client.claude.ClaudeApiClient`, `com.silporestockai.model.ConversationFlow`, `java.io.IOException`, `java.io.UncheckedIOException`, `java.nio.charset.StandardCharsets`, `java.time.Instant`, `java.time.format.DateTimeParseException`, `java.util.Map`, `org.springframework.beans.factory.annotation.Value`, `org.springframework.core.io.Resource`.

`TelegramRoutingService.java` — add the flow-check branch with the others:

```java
        if (flow == ConversationFlow.SCHEDULED_TASK_EDIT) {
            scheduledTaskManagementService.handleEditReply(user, incoming);
            return;
        }
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.silporestockai.integration.ScheduledTaskManagementIntegrationTest"`
Expected: PASS.

**Step 5: Run the full suite**

Run: `make format && make test`
Expected: all green. This closes out task 33.

**Step 6: Commit**

```bash
git add src/main/resources/prompts/scheduled-task-edit-system.txt \
        src/main/java/com/silporestockai/service/ScheduledTaskManagementService.java \
        src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java \
        src/test/java/com/silporestockai/integration/ScheduledTaskManagementIntegrationTest.java
git commit -m "Add edit for pending scheduled ad-hoc purchases (task 33)"
```

---

## Self-Review Notes

- **Spec coverage:** acceptance criterion 1 (list with theme+time) → Task 2; criterion 2 (edit updates in place, sweep respects it) → Task 4 plus Task 1's ordering test; criterion 3 (cancel + sweep skips it) → Task 3; criterion 4 (empty state) → Task 2; criterion 5 (four-button menu, no duplicate keyboard-building code — this task is the one that touches `MainMenuKeyboard`, once) → Task 2. Criterion 6 (manual test: schedule via chat, edit, confirm sweep respects it; schedule, cancel, confirm it never fires) is a live-Telegram check — add to `docs/RUNBOOK.md` after Task 4 lands, the same way task 31's live-classification checklist was added; not a coded task.
- **Type consistency:** `ScheduledTaskManagementService.CONTEXT_TASK_ID` (Task 4) is the same string key `handleButtonTap`'s edit branch (also Task 4) writes and `handleEditReply` reads — defined once, used both places. `EditSlots` matches the two-field JSON shape the new prompt asks for.
- **Known deliberate omission:** the spec's optional section 7 (recently-fired/COMPLETED secondary section) is explicitly out of scope per the task's own "must-have scope is the pending/editable list" — no task implements it.
