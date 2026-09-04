# Chat-first intent router (task 31, scoped) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Free text, when no conversation flow is active and no slash command matched, is classified into
one of 8 intents and dispatched to the service that already owns that capability — additive to the
existing command surface, not a replacement for it.

**Architecture:** One new `IntentRouterService` (Claude structured-output classification + dispatch table)
wired as the new fallback in `TelegramRoutingService`. One new small vertical slice
(`ScheduledAdHocTask` entity/repo/service/scheduler) for the "schedule a future ad-hoc purchase" intent,
mirroring `SpecialModeService`/`SpecialModeScheduler`'s existing sweep pattern exactly. `MainMenuKeyboard`
swaps to three buttons.

**Tech Stack:** Spring Boot 4, `ClaudeApiClient.completeStructured`, Liquibase, existing
`AdHocOrderService`/`SpecialModeService`/`MealPlanService`/`ShoppingListBuilderService`.

**Spec:** `docs/superpowers/specs/2026-09-05-intent-router-design.md`

## Global Constraints

- No changes to `AdHocOrderService`, `SpecialModeService`, `MealPlanService`, or `ShoppingListService`'s
  own business logic — this task is dispatch only (spec's explicit constraint, carried from Notion task
  31's own out-of-scope note).
- Every existing slash-command branch in `TelegramRoutingService` stays untouched — this task is additive.
- `intent` in the classifier's structured-output record is a `String`, parsed defensively via
  `IntentType.valueOf(...)` with an `UNKNOWN` fallback — never a Claude-schema-derived enum.
- Confidence threshold for acting on a classified intent: `0.6`. Below that, or `UNKNOWN`, ask a
  clarifying question.

---

### Task 1: `scheduled_ad_hoc_task` schema + entity + repository

**Files:**
- Create: `src/main/resources/db/changelog/changes/022-scheduled-ad-hoc-task.yaml`
- Create: `src/main/java/com/silporestockai/entity/ScheduledAdHocTask.java`
- Create: `src/main/java/com/silporestockai/model/ScheduledAdHocTaskStatus.java`
- Create: `src/main/java/com/silporestockai/repository/ScheduledAdHocTaskRepository.java`
- Test: `src/test/java/com/silporestockai/integration/SchemaRoundTripIntegrationTest.java` (extend, if that
  file already round-trips every entity — check first; if it uses a loop over all `@Entity` classes,
  nothing to add).

**Interfaces:**
- Produces: `ScheduledAdHocTask` fields — `id` (UUID), `userId` (UUID), `triggerAt` (Instant),
  `themeDescription` (String), `status` (`ScheduledAdHocTaskStatus`: `PENDING`, `FIRED`, `CANCELLED`),
  `createdAt` (Instant). `ScheduledAdHocTaskRepository.findByStatusAndTriggerAtBefore(status, Instant)`.

- [ ] **Step 1: Write the changelog**

```yaml
databaseChangeLog:
  - changeSet:
      id: 022-scheduled-ad-hoc-task
      author: komora
      comment: >-
        Task 31: a free-text "закажи до п'ятниці..." request is scheduled here rather than fired
        immediately — the sweep in AdHocScheduleScheduler picks it up when triggerAt arrives.
      changes:
        - createTable:
            tableName: scheduled_ad_hoc_task
            columns:
              - column: { name: id, type: UUID, constraints: { primaryKey: true, nullable: false } }
              - column: { name: user_id, type: UUID, constraints: { nullable: false } }
              - column: { name: trigger_at, type: TIMESTAMP WITH TIME ZONE, constraints: { nullable: false } }
              - column: { name: theme_description, type: VARCHAR(256), constraints: { nullable: false } }
              - column: { name: status, type: VARCHAR(16), constraints: { nullable: false } }
              - column: { name: created_at, type: TIMESTAMP WITH TIME ZONE, constraints: { nullable: false } }
```

- [ ] **Step 2: Entity**

```java
package com.silporestockai.entity;

import com.silporestockai.model.ScheduledAdHocTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A free-text "закажи до п'ятниці..." request, waiting for its trigger time. */
@Entity
@Table(name = "scheduled_ad_hoc_task")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledAdHocTask {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "trigger_at", nullable = false)
    private Instant triggerAt;

    @Column(name = "theme_description", nullable = false, length = 256)
    private String themeDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ScheduledAdHocTaskStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

- [ ] **Step 3: Status enum**

```java
package com.silporestockai.model;

/** Where a scheduled ad-hoc purchase is. */
public enum ScheduledAdHocTaskStatus {
    PENDING,
    FIRED,
    CANCELLED
}
```

- [ ] **Step 4: Repository**

```java
package com.silporestockai.repository;

import com.silporestockai.entity.ScheduledAdHocTask;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledAdHocTaskRepository extends JpaRepository<ScheduledAdHocTask, UUID> {
    List<ScheduledAdHocTask> findByStatusAndTriggerAtBefore(ScheduledAdHocTaskStatus status, Instant instant);
}
```

- [ ] **Step 5: Run the full test suite, confirm the new entity boots cleanly under `ddl-auto: validate`**

Run: `./gradlew test --tests "com.silporestockai.integration.SchemaRoundTripIntegrationTest"`
Expected: PASS (or, if that test doesn't cover every entity generically, at minimum any
`@SpringBootTest` boots — run one, e.g. `OnboardingFlowIntegrationTest`, and confirm no Liquibase error)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/changelog/changes/022-scheduled-ad-hoc-task.yaml \
        src/main/java/com/silporestockai/entity/ScheduledAdHocTask.java \
        src/main/java/com/silporestockai/model/ScheduledAdHocTaskStatus.java \
        src/main/java/com/silporestockai/repository/ScheduledAdHocTaskRepository.java
git commit -m "Add scheduled_ad_hoc_task schema for task 31's scheduled purchases"
```

---

### Task 2: `AdHocScheduleService` + `AdHocScheduleScheduler`

**Files:**
- Create: `src/main/java/com/silporestockai/service/AdHocScheduleService.java`
- Create: `src/main/java/com/silporestockai/job/AdHocScheduleScheduler.java`
- Create: `src/main/java/com/silporestockai/config/AdHocScheduleProperties.java`
- Modify: `src/main/resources/application.yml` (new `komora.ad-hoc-schedule.sweep-cron` key)
- Modify: `src/main/resources/application.yml` or `config/` for `@ConfigurationPropertiesScan` if not
  already global (check `SpecialModeConfig.java` — likely nothing extra needed, same registration style)
- Test: `src/test/java/com/silporestockai/integration/AdHocScheduleIntegrationTest.java`

**Interfaces:**
- Consumes: `ScheduledAdHocTaskRepository` (Task 1), `AdHocOrderService.buildAdHocOrder(User, String,
  Instant)` (already exists, task 24), `UserRepository.findById`.
- Produces: `AdHocScheduleService.schedule(User user, String themeDescription, Instant triggerAt)` (saves a
  `PENDING` row, sends a "Заплановано на ..." confirmation), `AdHocScheduleService.sweepDue()` (returns
  `int` — count fired, mirroring `SpecialModeService.sweepExpired()`'s own return-count convention).

- [ ] **Step 1: Write the failing test — schedule, then sweep before due time does nothing**

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.ScheduledAdHocTaskStatus;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.AdHocScheduleService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("a scheduled ad-hoc purchase fires only once its trigger time has passed")
class AdHocScheduleIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 14801L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();

    @Autowired private AdHocScheduleService adHocScheduleService;
    @Autowired private ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;
    @Autowired private UserAccountService userAccountService;
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private SilpoOAuthTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenCipher tokenCipher;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer("3030:stub-bot-token");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart", "silpo_get_shopping_cart_by_id", "silpo_get_time_slots",
                    "silpo_add_or_update_cart_products", "silpo_get_promotions"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> "3030:stub-bot-token");
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
        MCP.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        MCP.reset();
        scheduledAdHocTaskRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder().id(UUID.randomUUID()).userId(user.getId())
                .householdSize(2).build());
        tokenRepository.save(SilpoOAuthToken.builder().userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-s\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-s","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[],"total":0,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-s",\
                "checkoutMobileLink":"silpo://checkout/cart-s"}""");
        MCP.respondToTool("silpo_get_promotions", """
                {"promotions":[{"name":"Чіпси Lays","productId":"p-1","price":40,"oldPrice":60}]}""");
    }

    @Test
    void sweepingBeforeTheTriggerTimeDoesNothing() {
        adHocScheduleService.schedule(user, "вечір п'ятниці", Instant.now().plus(2, ChronoUnit.DAYS));

        int fired = adHocScheduleService.sweepDue();

        assertThat(fired).isZero();
        assertThat(scheduledAdHocTaskRepository.findAll().getFirst().getStatus())
                .isEqualTo(ScheduledAdHocTaskStatus.PENDING);
    }

    @Test
    void sweepingAfterTheTriggerTimeBuildsTheCartAndMarksItFired() {
        adHocScheduleService.schedule(user, "вечір п'ятниці", Instant.now().minus(1, ChronoUnit.HOURS));

        int fired = adHocScheduleService.sweepDue();

        assertThat(fired).isEqualTo(1);
        assertThat(scheduledAdHocTaskRepository.findAll().getFirst().getStatus())
                .isEqualTo(ScheduledAdHocTaskStatus.FIRED);
        assertThat(TELEGRAM.sentMessages()).isNotEmpty();
    }

    @Test
    void sweepingTwiceInARowFiresOnlyOnce() {
        adHocScheduleService.schedule(user, "вечір п'ятниці", Instant.now().minus(1, ChronoUnit.HOURS));

        adHocScheduleService.sweepDue();
        int firedAgain = adHocScheduleService.sweepDue();

        assertThat(firedAgain).isZero();
    }
}
```

- [ ] **Step 2: Run it, confirm it fails to compile (`AdHocScheduleService` doesn't exist yet)**

Run: `./gradlew test --tests "com.silporestockai.integration.AdHocScheduleIntegrationTest" -q`
Expected: compile error, `cannot find symbol: class AdHocScheduleService`

- [ ] **Step 3: `AdHocScheduleProperties`**

```java
package com.silporestockai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** How often the scheduled-ad-hoc-purchase sweep looks for a task whose trigger time has passed. */
@ConfigurationProperties(prefix = "komora.ad-hoc-schedule")
public record AdHocScheduleProperties(String sweepCron) {}
```

Add to `application.yml`, right after the `special-mode` block:

```yaml
  ad-hoc-schedule:
    sweep-cron: ${AD_HOC_SCHEDULE_SWEEP_CRON:0 */15 * * * *}
```

(Every 15 minutes — an ad-hoc purchase's trigger time is usually "by Friday evening," not
minute-precision; check `@ConfigurationPropertiesScan` is already global before adding a new annotation —
`SpecialModeProperties` has none on itself, so whatever registers that one covers this one too.)

- [ ] **Step 4: `AdHocScheduleService`**

```java
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

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm").withZone(ZoneId.of("Europe/Kyiv"));

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
            userRepository.findById(task.getUserId()).ifPresentOrElse(
                    user -> {
                        adHocOrderService.buildAdHocOrder(user, task.getThemeDescription(), task.getTriggerAt());
                        task.setStatus(ScheduledAdHocTaskStatus.FIRED);
                        scheduledAdHocTaskRepository.save(task);
                    },
                    () -> log.warn("scheduled ad-hoc task {} has no matching user; leaving it pending",
                            task.getId()));
        }
        if (!due.isEmpty()) {
            log.info("fired {} scheduled ad-hoc purchases", due.size());
        }
        return due.size();
    }
}
```

- [ ] **Step 5: `AdHocScheduleScheduler`**

```java
package com.silporestockai.job;

import com.silporestockai.service.AdHocScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the scheduled-ad-hoc-purchase sweep on a clock. All logic lives in {@link AdHocScheduleService}. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdHocScheduleScheduler {

    private final AdHocScheduleService adHocScheduleService;

    @Scheduled(cron = "${komora.ad-hoc-schedule.sweep-cron}")
    public void sweepDueAdHocPurchases() {
        adHocScheduleService.sweepDue();
    }
}
```

- [ ] **Step 6: Run the test, confirm it passes**

Run: `./gradlew test --tests "com.silporestockai.integration.AdHocScheduleIntegrationTest" -q`
Expected: PASS, 3 tests

- [ ] **Step 7: `make format`, then commit**

```bash
git add src/main/java/com/silporestockai/service/AdHocScheduleService.java \
        src/main/java/com/silporestockai/job/AdHocScheduleScheduler.java \
        src/main/java/com/silporestockai/config/AdHocScheduleProperties.java \
        src/main/resources/application.yml \
        src/test/java/com/silporestockai/integration/AdHocScheduleIntegrationTest.java
git commit -m "Add the scheduled-ad-hoc-purchase sweep (task 31's AD_HOC_SCHEDULED_PURCHASE intent)"
```

---

### Task 3: `IntentRouterService` — classification + dispatch, wired additively

**Files:**
- Create: `src/main/java/com/silporestockai/service/IntentRouterService.java`
- Create: `src/main/resources/prompts/intent-router-system.txt`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/MainMenuKeyboard.java`
- Test: `src/test/java/com/silporestockai/integration/IntentRouterIntegrationTest.java`

**Interfaces:**
- Consumes: `ClaudeApiClient.completeStructured` (existing), `AdHocScheduleService.schedule` (Task 2),
  `SpecialModeService.triggerGastritis/startMassGainSetup/toggleUaOnly` (existing, task 25),
  `MealPlanService.regenerateWithAdjustment` (existing, task 07), `ShoppingListService.deriveFromMealPlan`
  (existing, task 08), `ShoppingListBuilderService.present/askForInput` (existing, task 20/08).
- Produces: `IntentRouterService.route(User user, String text)` — called from `TelegramRoutingService`'s
  fallback branch.

- [ ] **Step 1: Write the system prompt**

`src/main/resources/prompts/intent-router-system.txt`:

```
Ти класифікуєш повідомлення користувача чат-бота "Комора" (агент для замовлення продуктів) в один із
намірів нижче. Відповідай лише структурованим об'єктом — без пояснень.

Намір (intent) — рядок, ОБОВ'ЯЗКОВО один із:
- AD_HOC_SCHEDULED_PURCHASE — разове замовлення на конкретний час/подію, окремо від тижневого плану.
  Приклади: "закажи до п'ятниці вино та сир по знижці", "замов щось на вечір під фільм".
  Заповни themeDescription (коротко, чого хоче користувач) і targetDateTimeIso (ISO-8601, якщо час
  можна визначити — інакше null).
- SPECIAL_MODE_MEDICAL_GASTRITIS — хвороба, гастрит, щадне харчування.
  Приклади: "я захворів, гастрит", "болить шлунок, потрібна дієта".
- SPECIAL_MODE_LEANER — зробити раціон менш калорійним, без зміни режиму набору маси.
  Приклади: "зроби менш калорійним", "менше калорій, будь ласка".
- SPECIAL_MODE_MASS_GAIN — набір маси, більше білка/протеїну.
  Приклади: "хочу набрати масу", "більше протеїну".
- FILTER_UA_PRODUCER_ONLY — лише товари українських виробників.
  Приклади: "шукай тільки український виробник", "тільки укр виробника".
- LIST_VIEW — показати або редагувати поточний список.
  Приклади: "покажи список", "що в списку", "онови список".
- HELP — просить довідку/інструкцію.
  Приклади: "що ти вмієш", "допомога", "як тобою користуватись".
- UNKNOWN — нічого з переліченого не підходить, або незрозуміло.

confidence — число від 0 до 1, наскільки ти впевнений.
themeDescription і targetDateTimeIso — заповнюй лише для AD_HOC_SCHEDULED_PURCHASE, інакше null.
```

- [ ] **Step 2: Write the failing test**

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.ConversationStateRepository;
import com.silporestockai.repository.ScheduledAdHocTaskRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.time.Instant;
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

@DisplayName("free text, outside any active flow, is classified into an intent and dispatched")
class IntentRouterIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "4040:stub-bot-token";
    private static final long CHAT_ID = 15901L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserAccountService userAccountService;
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private ConversationStateRepository conversationStateRepository;
    @Autowired private ScheduledAdHocTaskRepository scheduledAdHocTaskRepository;
    @Autowired private SilpoOAuthTokenRepository tokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenCipher tokenCipher;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of("silpo_get_my_family"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubAnthropicServer startClaude() {
        try {
            return new StubAnthropicServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
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
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder().id(UUID.randomUUID()).userId(user.getId())
                .householdSize(2).build());
        tokenRepository.save(SilpoOAuthToken.builder().userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
    }

    private void sendText(int updateId, String text) throws Exception {
        mockMvc.perform(post("/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "text":"%s"}}""".formatted(updateId, updateId, CHAT_ID, text)))
                .andExpect(status().isOk());
    }

    @Test
    void scheduledPurchaseIntentSchedulesRatherThanBuildsImmediately() throws Exception {
        CLAUDE.respondWithText("""
                {"intent":"AD_HOC_SCHEDULED_PURCHASE","confidence":0.9,\
                "themeDescription":"вино та сир зі знижкою","targetDateTimeIso":"2026-09-11T18:00:00Z"}""");

        sendText(1, "закажи до п'ятниці вино та сир по знижці");

        assertThat(scheduledAdHocTaskRepository.findAll()).hasSize(1);
        assertThat(scheduledAdHocTaskRepository.findAll().getFirst().getThemeDescription())
                .isEqualTo("вино та сир зі знижкою");
    }

    @Test
    void lowConfidenceAsksAClarifyingQuestionInsteadOfGuessing() throws Exception {
        CLAUDE.respondWithText("""
                {"intent":"UNKNOWN","confidence":0.2,"themeDescription":null,"targetDateTimeIso":null}""");

        sendText(1, "щось незрозуміле бурмотіння");

        assertThat(TELEGRAM.sentMessages()).isNotEmpty();
        assertThat(scheduledAdHocTaskRepository.findAll()).isEmpty();
    }

    @Test
    void helpIntentRendersTheStaticInstructionMessage() throws Exception {
        CLAUDE.respondWithText("""
                {"intent":"HELP","confidence":0.95,"themeDescription":null,"targetDateTimeIso":null}""");

        sendText(1, "що ти вмієш?");

        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText()).contains("Список");
    }

    @Test
    void uaOnlyIntentTogglesTheFlag() throws Exception {
        CLAUDE.respondWithText("""
                {"intent":"FILTER_UA_PRODUCER_ONLY","confidence":0.9,"themeDescription":null,\
                "targetDateTimeIso":null}""");

        sendText(1, "шукай тільки український виробник");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        assertThat(userProfileRepository.findByUserId(userId).orElseThrow().isOnlyUaProducer()).isTrue();
    }

    @Test
    void existingSlashCommandsStillWorkUnchanged() throws Exception {
        // The additive-rollout guarantee: /blackout still reaches BlackoutModeService directly, no
        // classification call happens for it at all.
        sendText(1, "/blackout");

        assertThat(CLAUDE.requestCount()).isZero();
    }
}
```

(Check `UserProfile`'s exact UA-only getter name — `onlyUaProducer`/`isOnlyUaProducer` — against the
entity before running; `SpecialModeIntegrationTest`'s own `uaonlyCommandTogglesTheFlag` test already
asserts this field, copy its exact accessor call. Same for `StubAnthropicServer.requestCount()` — check the
class for its real name if this doesn't compile; every other stub in this codebase exposes a call-count
accessor of some form, e.g. `StubMcpServer.callCount`.)

- [ ] **Step 3: Run it, confirm it fails to compile (`IntentRouterService` doesn't exist)**

Run: `./gradlew test --tests "com.silporestockai.integration.IntentRouterIntegrationTest" -q`

- [ ] **Step 4: `IntentRouterService`**

```java
package com.silporestockai.service;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * The chat-first control surface (task 31): free text that reaches here (no active conversation flow, no
 * matching slash command) is classified into one of a fixed set of intents and dispatched to whichever
 * service already owns that capability. Classification only — no business logic duplicated from the
 * services it dispatches to.
 */
@Slf4j
@Service
public class IntentRouterService {

    private static final double CONFIDENCE_THRESHOLD = 0.6;

    private static final String HELP_TEXT =
            """
            Ось що можна написати мені звичайним текстом:

            — «Список» — показати поточний список покупок.
            — «Закажи до п'ятниці вино та сир по знижці» — разове замовлення на конкретний час.
            — «Я захворів, гастрит» — тимчасово перемкнутись на щадне харчування.
            — «Зроби менш калорійним» — зменшити калорійність поточного плану.
            — «Хочу набрати масу» / «більше протеїну» — почати набір маси.
            — «Шукай тільки український виробник» — фільтрувати товари за походженням.
            — «Анкета» — оновити відповіді анкети.""";

    private final ClaudeApiClient claudeApiClient;
    private final AdHocScheduleService adHocScheduleService;
    private final SpecialModeService specialModeService;
    private final MealPlanService mealPlanService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final TelegramOutboundService telegramOutboundService;
    private final String systemPrompt;

    public IntentRouterService(
            ClaudeApiClient claudeApiClient,
            AdHocScheduleService adHocScheduleService,
            SpecialModeService specialModeService,
            MealPlanService mealPlanService,
            ShoppingListService shoppingListService,
            ShoppingListBuilderService shoppingListBuilderService,
            TelegramOutboundService telegramOutboundService,
            @Value("classpath:prompts/intent-router-system.txt") Resource systemPromptResource) {
        this.claudeApiClient = claudeApiClient;
        this.adHocScheduleService = adHocScheduleService;
        this.specialModeService = specialModeService;
        this.mealPlanService = mealPlanService;
        this.shoppingListService = shoppingListService;
        this.shoppingListBuilderService = shoppingListBuilderService;
        this.telegramOutboundService = telegramOutboundService;
        this.systemPrompt = read(systemPromptResource);
    }

    public void route(User user, String text) {
        ClassifiedIntent classified;
        try {
            classified = claudeApiClient.completeStructured(systemPrompt, text, ClassifiedIntent.class);
        } catch (RuntimeException e) {
            log.warn("could not classify intent for text, asking a clarifying question", e);
            askClarifyingQuestion(user);
            return;
        }
        IntentType intent = parse(classified.intent());
        if (intent == IntentType.UNKNOWN || classified.confidence() < CONFIDENCE_THRESHOLD) {
            askClarifyingQuestion(user);
            return;
        }
        log.info("user {} classified as {} (confidence {})", user.getId(), intent, classified.confidence());
        switch (intent) {
            case AD_HOC_SCHEDULED_PURCHASE -> scheduleAdHoc(user, classified);
            case SPECIAL_MODE_MEDICAL_GASTRITIS -> specialModeService.triggerGastritis(user);
            case SPECIAL_MODE_LEANER -> adjustPlan(user, "Зроби раціон менш калорійним.");
            case SPECIAL_MODE_MASS_GAIN -> {
                telegramOutboundService.sendMessage(
                        user.getTelegramChatId(),
                        "До речі, для набору маси часто беруть протеїн або гейнер — можу підказати, якщо цікаво.");
                specialModeService.startMassGainSetup(user);
            }
            case FILTER_UA_PRODUCER_ONLY -> specialModeService.toggleUaOnly(user);
            case LIST_VIEW -> shoppingListBuilderService.askForInput(user);
            case HELP -> telegramOutboundService.sendMessage(user.getTelegramChatId(), HELP_TEXT);
            case UNKNOWN -> askClarifyingQuestion(user);
        }
    }

    private void scheduleAdHoc(User user, ClassifiedIntent classified) {
        Instant triggerAt = parseTriggerAt(classified.targetDateTimeIso());
        String theme = classified.themeDescription() == null || classified.themeDescription().isBlank()
                ? "щось смачне"
                : classified.themeDescription();
        adHocScheduleService.schedule(user, theme, triggerAt);
    }

    private void adjustPlan(User user, String instruction) {
        MealPlan plan = mealPlanService.regenerateWithAdjustment(user.getId(), instruction);
        List<ShoppingListItem> items =
                shoppingListService.deriveFromMealPlan(plan.getId(), plan.getSourceType());
        shoppingListBuilderService.present(user, items);
    }

    private void askClarifyingQuestion(User user) {
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                "Не зовсім зрозумів. Напиши, будь ласка, інакше, або напиши «Інструкція», щоб побачити приклади.");
    }

    /** No extractable time defaults to "soon" — one hour out, so the sweep picks it up on its next pass. */
    private static Instant parseTriggerAt(String iso) {
        if (iso == null || iso.isBlank()) {
            return Instant.now().plusSeconds(3600);
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return Instant.now().plusSeconds(3600);
        }
    }

    private static IntentType parse(String raw) {
        if (raw == null) {
            return IntentType.UNKNOWN;
        }
        try {
            return IntentType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return IntentType.UNKNOWN;
        }
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the intent router system prompt", e);
        }
    }

    private enum IntentType {
        AD_HOC_SCHEDULED_PURCHASE,
        SPECIAL_MODE_MEDICAL_GASTRITIS,
        SPECIAL_MODE_LEANER,
        SPECIAL_MODE_MASS_GAIN,
        FILTER_UA_PRODUCER_ONLY,
        LIST_VIEW,
        HELP,
        UNKNOWN
    }

    private record ClassifiedIntent(
            String intent, double confidence, String themeDescription, String targetDateTimeIso) {}
}
```

- [ ] **Step 5: Wire into `TelegramRoutingService`**

Replace the tail of `handle(...)` — the existing block:

```java
        if (incoming instanceof TelegramIncomingUpdate.Text freeText
                && specialModeService.detectGastritisIntent(freeText.text())) {
            specialModeService.triggerGastritis(user);
            return;
        }
        telegramOutboundService.sendMessageWithMainMenu(
                incoming.chatId(),
                "Профіль уже є. Обери дію нижче або напиши /list, /reorder, /voice, /blackout, /calendar, "
                        + "/masgain, /uaonly чи /normal.");
```

with:

```java
        if (incoming instanceof TelegramIncomingUpdate.Text freeText) {
            intentRouterService.route(user, freeText.text());
            return;
        }
        telegramOutboundService.sendMessageWithMainMenu(
                incoming.chatId(), "Скористайся кнопками нижче або напиши, що потрібно.");
```

Add the field (`private final IntentRouterService intentRouterService;`) alongside the other
`@RequiredArgsConstructor`-injected fields. `SpecialModeService.detectGastritisIntent` stays in
`SpecialModeService` itself (still covered by `SpecialModeIntegrationTest`'s own unit-level test on the
method) — it is simply no longer *called* from here, since `IntentRouterService` now covers that
classification as part of its own unified call.

- [ ] **Step 6: `MainMenuKeyboard`, three buttons**

```java
package com.silporestockai.service.telegram;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

/**
 * The persistent bottom keyboard (task 31): exactly three buttons. Everything else is free text through
 * {@link IntentRouterService}. {@link TelegramRoutingService}'s slash-command branches for the retired
 * buttons (blackout, reorder, voice, calendar, normal) still work if typed — only the visible keyboard
 * shrank.
 */
public final class MainMenuKeyboard {

    public static final String LIST = "📝 Список";
    public static final String FORM = "🧾 Анкета";
    public static final String HELP = "❓ Інструкція";

    private MainMenuKeyboard() {}

    public static ReplyKeyboardMarkup markup() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(LIST, FORM, HELP))
                .resizeKeyboard(true)
                .build();
    }
}
```

Check every existing reference to the removed constants (`REORDER`, `VOICE`, `BLACKOUT`, `CALENDAR`,
`NORMAL`) before deleting them — `TelegramRoutingService`'s `matches(text, command, buttonLabel)` calls for
those commands pass the constant as their second argument. Since the buttons themselves are gone but the
commands must keep working (additive-rollout guarantee), change those specific `matches(...)` calls to
pass `""` as the button label instead of the removed constant (exactly the pattern already used for
`/uaonly` and `/masgain`, which never had their own button either) rather than deleting the branches.

- [ ] **Step 7: Run the new test, then the full suite**

Run: `./gradlew test --tests "com.silporestockai.integration.IntentRouterIntegrationTest" -q`
Expected: PASS, 5 tests

Run: `./gradlew test -q`
Expected: PASS (aside from any pre-existing, already-documented flaky/OOM noise on this machine — compare
the failing-class list against what was already known flaky before this task; anything new is a real
regression and must be fixed before moving on)

- [ ] **Step 8: `make format`, then commit**

```bash
git add src/main/java/com/silporestockai/service/IntentRouterService.java \
        src/main/resources/prompts/intent-router-system.txt \
        src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java \
        src/main/java/com/silporestockai/service/telegram/MainMenuKeyboard.java \
        src/test/java/com/silporestockai/integration/IntentRouterIntegrationTest.java
git commit -m "Add IntentRouterService: free-text intent classification and dispatch (task 31, scoped)"
```

---

### Task 4: manual-verification checklist + Notion/demo-doc updates

Not a code task — a documentation deliverable, same shape as task 28's Task 4.

- [ ] Add a short section to `docs/RUNBOOK.md`: "Task 31: verify free-text intents live in Telegram" —
  one line per intent, the exact phrase to type, and what should happen. Cannot be automated: judging
  whether Claude's live classification (not the stubbed one tests use) picks the right intent for a real
  ambiguous sentence needs a human.
- [ ] Update Notion task 31's status per whatever actually passed tonight — see this plan's own Task 3
  Step 7 test run and the deferred-scope note in the spec. Likely **In progress**, not Done: the "Анкета"
  reopen flow (acceptance criterion 7) is deferred by design.
- [ ] Add a line to the "Сценарій демо-запису" Notion doc if the three-button menu changes any existing
  demo step's description of what the bot's main menu looks like.
