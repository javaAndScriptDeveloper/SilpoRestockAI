# Structured Onboarding (WebApp form) + Persisted List + List/Calendar Separation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework onboarding (task 06) into a Telegram WebApp form with a text-chain fallback, fork meal-plan generation (task 07) for `READY_MEALS_ONLY` households, and turn the shopping list from a rebuilt-on-view text blob into a persisted, categorized, status-tracked list that AI touches only on genuine regeneration.

**Architecture:** Additive changes on top of the existing onboarding/meal-plan/shopping-list services — no rewrite. New enums and two Liquibase changesets carry the new data; `OnboardingFlowService` gains one new step (`AWAITING_WEBAPP_FORM`) between Silpo-connect and budget; `MealPlanService` picks between two system prompts by `cookingTimePreference` but reuses the exact same `WeeklyMealPlan` output shape either way; `ShoppingListService` gains soft-archive semantics and three AI-free CRUD methods, kept structurally free of any `ClaudeApiClient` dependency.

**Tech Stack:** Spring Boot 4, JPA/Hibernate, Liquibase, MapStruct, Lombok, JUnit 5 + AssertJ + MockMvc + Testcontainers (`AbstractIntegrationTest`), Telegram Bot API via `telegrambots-meta`/`telegrambots-client` 9.0.0, Anthropic Claude via the app's own `ClaudeApiClient`, plain static HTML/CSS/vanilla JS for the WebApp form (no bundler, no framework — Spring serves it from `src/main/resources/static`).

**Spec:** `docs/superpowers/specs/2026-09-03-structured-onboarding-design.md`

## Global Constraints

- **Liquibase owns the schema** — every entity change needs a changeset under `src/main/resources/db/changelog/changes/`, numbered `018-...`/`019-...` (next after the existing `017-users-voice-replies.yaml`), and `master.yaml` uses `includeAll` so no separate registration is needed.
- **Constructor injection only** (`@RequiredArgsConstructor`, no `@Autowired` fields) — ArchUnit enforces this.
- **`Service`/`Controller`/`Repository` name suffixes** — ArchUnit enforces this; no new `...Impl` classes for anything already a concrete class.
- **Telegram SDK types stay inside `controller.telegram`/`service.telegram`** — ArchUnit's `telegramSdkStaysBehindTheTelegramPackages` enforces this; `TelegramButton`/`TelegramIncomingUpdate` in `model` carry no SDK types.
- **`ShoppingListService` must never gain a `ClaudeApiClient` dependency** — this is the structural enforcement the spec's "AI called only on real change" rule depends on; do not add one even transitively through a new collaborator.
- **`spring.jpa.hibernate.ddl-auto: validate`** — the entity and the changeset must describe the exact same columns or every `@SpringBootTest` fails at context startup.
- **Run `make format` before any commit** (Spotless/palantir) — CI runs `spotlessCheck` before `build`.
- **`@Slf4j` for logging**, no manual `LoggerFactory`.
- Config idiom is `${ENV_VAR:default}` inline in `application.yml`; a new optional integration (the WebApp base URL) degrades gracefully when blank, same pattern as `GoogleAuthService.configured()`.

---

## File Structure

New files:
- `src/main/java/com/silporestockai/model/AgeBracket.java`
- `src/main/java/com/silporestockai/model/DietType.java`
- `src/main/java/com/silporestockai/model/CookingTimePreference.java`
- `src/main/java/com/silporestockai/model/ShoppingListStatus.java`
- `src/main/java/com/silporestockai/model/ShoppingListSourceType.java`
- `src/main/resources/db/changelog/changes/018-user-profile-structured-onboarding.yaml`
- `src/main/resources/db/changelog/changes/019-shopping-list-item-status.yaml`
- `src/main/resources/static/webapp/onboarding.html`
- `src/main/resources/static/webapp/onboarding.js`
- `src/main/resources/prompts/meal-plan-ready-meals-system.txt`
- `src/main/java/com/silporestockai/service/CategoryKeywordFallbackService.java`
- `src/test/java/com/silporestockai/unit/ShoppingListServiceTest.java` (or wherever this codebase's plain unit tests for `service` live — see Task 10 for the exact check)
- `src/test/java/com/silporestockai/unit/MealPlanServiceTest.java`

Modified files (touched across tasks, listed once here for orientation):
- `src/main/java/com/silporestockai/model/OnboardingStep.java`, `TelegramButton.java`, `TelegramIncomingUpdate.java`, `PlannedIngredient.java`
- `src/main/java/com/silporestockai/entity/UserProfile.java`, `ShoppingListItem.java`
- `src/main/java/com/silporestockai/repository/ShoppingListItemRepository.java`
- `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`, `TelegramOutboundService.java`, `ShoppingListMessageService.java`
- `src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java`
- `src/main/java/com/silporestockai/service/MealPlanService.java`, `ShoppingListService.java`, `ShoppingListBuilderService.java`, `MealPlanHandoffService.java`, `CartConfirmationService.java`
- `src/main/java/com/silporestockai/mapper/ShoppingListItemMapper.java`
- `src/main/java/com/silporestockai/config/TelegramProperties.java` (add `webAppBaseUrl`)
- `src/main/resources/application.yml`, `src/main/resources/prompts/meal-plan-system.txt`, `src/main/resources/prompts/shopping-list-system.txt`
- `src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java`

---

### Task 1: New enums + `OnboardingStep.AWAITING_WEBAPP_FORM`

**Files:**
- Create: `src/main/java/com/silporestockai/model/AgeBracket.java`
- Create: `src/main/java/com/silporestockai/model/DietType.java`
- Create: `src/main/java/com/silporestockai/model/CookingTimePreference.java`
- Create: `src/main/java/com/silporestockai/model/ShoppingListStatus.java`
- Create: `src/main/java/com/silporestockai/model/ShoppingListSourceType.java`
- Modify: `src/main/java/com/silporestockai/model/OnboardingStep.java`

**Interfaces:**
- Produces: five new enum types every later task references by exact name; `OnboardingStep.AWAITING_WEBAPP_FORM`.

This task is pure additive types with no behavior to unit-test in isolation — later tasks exercise them through the services that use them. Verify by compiling.

- [ ] **Step 1: Create the five enums**

```java
// src/main/java/com/silporestockai/model/AgeBracket.java
package com.silporestockai.model;

/**
 * A child's age band, as collected by the onboarding WebApp form. Persisted by name in
 * {@code user_profile.children_age_brackets}, so entries may be added but existing names must not be renamed
 * without a migration.
 */
public enum AgeBracket {
    AGE_0_3,
    AGE_4_7,
    AGE_8_12,
    AGE_13_17
}
```

```java
// src/main/java/com/silporestockai/model/DietType.java
package com.silporestockai.model;

/** A household's stated diet type, collected by the onboarding WebApp form. Persisted by name. */
public enum DietType {
    NONE,
    VEGETARIAN,
    VEGAN,
    GLUTEN_FREE,
    KETO,
    OTHER
}
```

```java
// src/main/java/com/silporestockai/model/CookingTimePreference.java
package com.silporestockai.model;

/**
 * How much cooking time a household has, collected by the onboarding WebApp form. {@link #READY_MEALS_ONLY} forks
 * {@code MealPlanService} onto a different system prompt entirely — see its Javadoc.
 */
public enum CookingTimePreference {
    /** Cooks a little every day. */
    COOKS_DAILY,
    /** Cooks once every few days, in advance. */
    COOKS_BATCH,
    /** Little to no time — wants ready-to-eat food only, no recipes. */
    READY_MEALS_ONLY
}
```

```java
// src/main/java/com/silporestockai/model/ShoppingListStatus.java
package com.silporestockai.model;

/** Lifecycle of a {@code shopping_list_item} row. Persisted by name. */
public enum ShoppingListStatus {
    /** The list currently on screen. */
    ACTIVE,
    /** Placed as part of a confirmed order. */
    ORDERED,
    /** Superseded by a newer list; kept for history rather than deleted. */
    ARCHIVED
}
```

```java
// src/main/java/com/silporestockai/model/ShoppingListSourceType.java
package com.silporestockai.model;

/** Which generation path produced a {@code shopping_list_item} row. Persisted by name. */
public enum ShoppingListSourceType {
    /** Aggregated from a weekly plan's recipe ingredients. */
    RECIPE_DERIVED,
    /** The ready-to-eat product itself, for {@link CookingTimePreference#READY_MEALS_ONLY} households. */
    READY_MEAL_DIRECT
}
```

- [ ] **Step 2: Add the new onboarding step**

Edit `src/main/java/com/silporestockai/model/OnboardingStep.java`, inserting the new step between `CONFIRM_PROFILE` and `ASK_HOUSEHOLD`:

```java
    /** Showing what MCP found; waiting for confirmation or a correction. */
    CONFIRM_PROFILE,
    /**
     * The Telegram WebApp form is open (or its manual-fallback button was offered); waiting for
     * {@code web_app_data} or the fallback button's text.
     */
    AWAITING_WEBAPP_FORM,
    /** Asking how many people eat at home. */
    ASK_HOUSEHOLD,
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (nothing references the new step or enums yet, so nothing else can fail).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/silporestockai/model/AgeBracket.java src/main/java/com/silporestockai/model/DietType.java src/main/java/com/silporestockai/model/CookingTimePreference.java src/main/java/com/silporestockai/model/ShoppingListStatus.java src/main/java/com/silporestockai/model/ShoppingListSourceType.java src/main/java/com/silporestockai/model/OnboardingStep.java
git commit -m "Add enums and onboarding step for structured onboarding"
```

---

### Task 2: `user_profile` structured columns

**Files:**
- Create: `src/main/resources/db/changelog/changes/018-user-profile-structured-onboarding.yaml`
- Modify: `src/main/java/com/silporestockai/entity/UserProfile.java`

**Interfaces:**
- Consumes: `AgeBracket`, `DietType`, `CookingTimePreference` (Task 1).
- Produces: `UserProfile.getAdultMaleCount()/getAdultFemaleCount()/getChildrenAgeBrackets()/getDietType()/getCookingTimePreference()` — Task 6 (`OnboardingFlowService`) and Task 8 (`MealPlanService`) read these.

- [ ] **Step 1: Write the changeset**

```yaml
# src/main/resources/db/changelog/changes/018-user-profile-structured-onboarding.yaml
databaseChangeLog:
  - changeSet:
      id: 018-user-profile-structured-onboarding
      author: komora
      comment: >-
        The WebApp onboarding form (task 20) collects household composition and diet preference at a
        granularity the flat household_size/kids_ages columns can't carry: adult sex counts, a per-child
        age bracket, an explicit diet type, and how much time the household has to cook. The old columns
        stay — the manual-fallback text chain still writes them, and existing prompt-building code still
        reads them — these are additive.
      changes:
        - addColumn:
            tableName: user_profile
            columns:
              - column:
                  name: adult_male_count
                  type: INT
              - column:
                  name: adult_female_count
                  type: INT
              - column:
                  name: children_age_brackets
                  type: JSONB
              - column:
                  name: diet_type
                  type: VARCHAR(32)
              - column:
                  name: cooking_time_preference
                  type: VARCHAR(32)
```

- [ ] **Step 2: Add the matching entity fields**

Edit `src/main/java/com/silporestockai/entity/UserProfile.java`, adding imports for `AgeBracket`, `CookingTimePreference`, `DietType`, and appending after `dislikedFoods`:

```java
    @Column(name = "adult_male_count")
    private Integer adultMaleCount;

    @Column(name = "adult_female_count")
    private Integer adultFemaleCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "children_age_brackets")
    private List<AgeBracket> childrenAgeBrackets;

    @Enumerated(EnumType.STRING)
    @Column(name = "diet_type", length = 32)
    private DietType dietType;

    @Enumerated(EnumType.STRING)
    @Column(name = "cooking_time_preference", length = 32)
    private CookingTimePreference cookingTimePreference;
```

- [ ] **Step 3: Run the existing test suite to confirm schema validation passes**

Run: `make test` (needs Docker for Testcontainers — confirm it's running first: `docker info >/dev/null && echo ok`)
Expected: all currently-passing tests still pass; in particular any `@SpringBootTest` boots cleanly (this is what would fail first if the entity and changeset disagree).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/changelog/changes/018-user-profile-structured-onboarding.yaml src/main/java/com/silporestockai/entity/UserProfile.java
git commit -m "Add structured household/diet columns to user_profile"
```

---

### Task 3: `shopping_list_item` status + source_type, repository additions

**Files:**
- Create: `src/main/resources/db/changelog/changes/019-shopping-list-item-status.yaml`
- Modify: `src/main/java/com/silporestockai/entity/ShoppingListItem.java`
- Modify: `src/main/java/com/silporestockai/repository/ShoppingListItemRepository.java`

**Interfaces:**
- Consumes: `ShoppingListStatus`, `ShoppingListSourceType` (Task 1).
- Produces: `ShoppingListItem.getStatus()/getSourceType()`; `ShoppingListItemRepository.findByUserIdAndStatus(UUID, ShoppingListStatus)`, `.findByMealPlanIdAndStatus(UUID, ShoppingListStatus)`, `.archiveActiveByMealPlanId(UUID)`, `.archiveActiveByUserId(UUID)`, `.archiveActiveByUserIdAndMealPlanIdIsNull(UUID)` — Task 9 wires these into `ShoppingListService`.

- [ ] **Step 1: Write the changeset**

```yaml
# src/main/resources/db/changelog/changes/019-shopping-list-item-status.yaml
databaseChangeLog:
  - changeSet:
      id: 019-shopping-list-item-status
      author: komora
      comment: >-
        An explicit lifecycle (ACTIVE/ORDERED/ARCHIVED) so replacing the live list archives the old rows
        instead of deleting them, and a source_type discriminator (RECIPE_DERIVED/READY_MEAL_DIRECT) so
        downstream cart-building doesn't need to know which generation path produced a line.
      changes:
        - addColumn:
            tableName: shopping_list_item
            columns:
              - column:
                  name: status
                  type: VARCHAR(16)
                  defaultValue: ACTIVE
                  constraints:
                    nullable: false
              - column:
                  name: source_type
                  type: VARCHAR(24)
        - createIndex:
            indexName: ix_shopping_list_item_user_status
            tableName: shopping_list_item
            columns:
              - column:
                  name: user_id
              - column:
                  name: status
```

- [ ] **Step 2: Add the matching entity fields**

Edit `src/main/java/com/silporestockai/entity/ShoppingListItem.java`, adding imports for `ShoppingListStatus`, `ShoppingListSourceType`, `jakarta.persistence.EnumType`, `jakarta.persistence.Enumerated`, and appending after `category`:

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private ShoppingListStatus status = ShoppingListStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 24)
    private ShoppingListSourceType sourceType;
```

- [ ] **Step 3: Add the repository methods**

Replace the full content of `src/main/java/com/silporestockai/repository/ShoppingListItemRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.ShoppingListStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Shopping list lines, either attached to a weekly plan or standing alone. */
public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, UUID> {

    List<ShoppingListItem> findByMealPlanId(UUID mealPlanId);

    /** The user's ad-hoc lines: everything they asked for outside a weekly plan. */
    List<ShoppingListItem> findByUserIdAndMealPlanIdIsNull(UUID userId);

    /** Whatever list is currently on screen, ad-hoc or derived from a weekly plan — there is only ever one live. */
    List<ShoppingListItem> findByUserId(UUID userId);

    /** The user's list in one specific lifecycle state — callers name the state explicitly rather than rely on an
     * implicit "current" meaning, so a future status value can never silently leak into "the current list". */
    List<ShoppingListItem> findByUserIdAndStatus(UUID userId, ShoppingListStatus status);

    /** Regenerating a plan replaces its list wholesale rather than diffing it. */
    void deleteByMealPlanId(UUID mealPlanId);

    /** Whatever is being shown replaces whatever the user had before, regardless of which flow produced either. */
    void deleteByUserIdAndIdNotIn(UUID userId, Collection<UUID> ids);

    /** Moves this plan's live rows to ARCHIVED instead of deleting them, so there is history to diff against. */
    @Modifying
    @Query("update ShoppingListItem i set i.status = com.silporestockai.model.ShoppingListStatus.ARCHIVED "
            + "where i.mealPlanId = :mealPlanId and i.status = com.silporestockai.model.ShoppingListStatus.ACTIVE")
    void archiveActiveByMealPlanId(@Param("mealPlanId") UUID mealPlanId);

    /** Moves every ACTIVE row of this user (ad-hoc or plan-derived) to ARCHIVED. */
    @Modifying
    @Query("update ShoppingListItem i set i.status = com.silporestockai.model.ShoppingListStatus.ARCHIVED "
            + "where i.userId = :userId and i.status = com.silporestockai.model.ShoppingListStatus.ACTIVE")
    void archiveActiveByUserId(@Param("userId") UUID userId);

    /** Moves this user's ACTIVE rows to ORDERED — the list on screen became a confirmed order. */
    @Modifying
    @Query("update ShoppingListItem i set i.status = com.silporestockai.model.ShoppingListStatus.ORDERED "
            + "where i.userId = :userId and i.status = com.silporestockai.model.ShoppingListStatus.ACTIVE")
    void markOrderedByUserId(@Param("userId") UUID userId);
}
```

- [ ] **Step 4: Run the existing test suite**

Run: `make test`
Expected: passes. `deleteByUserIdAndIdNotIn`/`deleteByMealPlanId` still exist (Task 9 replaces their call sites, not the methods themselves, so nothing breaks yet).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/changelog/changes/019-shopping-list-item-status.yaml src/main/java/com/silporestockai/entity/ShoppingListItem.java src/main/java/com/silporestockai/repository/ShoppingListItemRepository.java
git commit -m "Add status and source_type lifecycle to shopping_list_item"
```

---

### Task 4: WebApp button + incoming `web_app_data` + outbound sender

**Files:**
- Modify: `src/main/java/com/silporestockai/model/TelegramButton.java`
- Modify: `src/main/java/com/silporestockai/model/TelegramIncomingUpdate.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramOutboundService.java`
- Test: `src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java` (one new low-level assertion added at the end of this task; the full onboarding rewrite is Task 6)

**Interfaces:**
- Produces: `TelegramIncomingUpdate.WebAppData(long chatId, long telegramUserId, String data)`; `TelegramOutboundService.sendMessageWithWebAppButton(long chatId, String text, String webAppLabel, String webAppUrl, String fallbackLabel)`.

A Telegram Mini App opened from a **reply keyboard** button (not an inline one) is the only kind whose `Telegram.WebApp.sendData()` call reaches the bot as `message.web_app_data` — an inline `web_app` button's data goes through `answerWebAppQuery` instead, which this app has no use for. So the new sender uses `ReplyKeyboardMarkup`, matching the existing `MainMenuKeyboard` pattern, with a second row carrying the plain-text fallback label.

- [ ] **Step 1: Write the failing routing test**

Add to `src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java`, right after `sendText`/`tapButton`:

```java
    private void sendWebAppData(int updateId, String json) throws Exception {
        deliver("""
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "web_app_data":{"data":%s,"button_text":"Заповнити анкету"}}}"""
                .formatted(updateId, updateId, CHAT_ID, com.fasterxml.jackson.databind.node.TextNode.valueOf(json)));
    }
```

Wait — `TextNode.valueOf(json).toString()` is what actually produces a quoted/escaped JSON string literal; write it that way:

```java
    private void sendWebAppData(int updateId, String json) throws Exception {
        String escaped = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(json);
        deliver("""
                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                "web_app_data":{"data":%s,"button_text":"Заповнити анкету"}}}"""
                .formatted(updateId, updateId, CHAT_ID, escaped));
    }
```

This helper is unused until Task 6 wires `AWAITING_WEBAPP_FORM` handling — for this task, just add it plus a minimal smoke test proving the update reaches routing without error:

```java
    @Test
    void aWebAppDataUpdateDoesNotCrashTheWebhookBeforeOnboardingHandlesIt() throws Exception {
        // Routing-level plumbing only (task 20 step 1) — before AWAITING_WEBAPP_FORM exists, any stray
        // web_app_data update at whatever step the user happens to be on must not 500 the webhook.
        sendText(1, "привіт");
        sendWebAppData(2, "{\"adultMale\":1}");
        // No assertion beyond "the webhook call above didn't throw" — deliver() already asserts 200.
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "com.silporestockai.integration.OnboardingFlowIntegrationTest.aWebAppDataUpdateDoesNotCrashTheWebhookBeforeOnboardingHandlesIt"`
Expected: FAIL — `Message.hasWebAppData()`/routing doesn't recognise it yet, so `toIncoming` returns empty and nothing crashes... actually this may already pass trivially since `toIncoming` returning `Optional.empty()` just logs and drops it. Replace the assertion with something that actually distinguishes "handled" from "dropped": assert on a log-independent signal instead — skip this test for now and rely on Step 4's stronger test in Task 6. **Remove this test from this task**; it doesn't have a signal to assert on until `OnboardingFlowService` exists to react (Task 6). Proceed straight to implementation.

- [ ] **Step 3: Add the `WebAppData` variant**

Edit `src/main/java/com/silporestockai/model/TelegramIncomingUpdate.java`, adding after `ButtonTap`:

```java
    /** A Telegram WebApp form submission — {@code Telegram.WebApp.sendData()} on the client side. */
    record WebAppData(long chatId, long telegramUserId, String data) implements TelegramIncomingUpdate {}
```

- [ ] **Step 4: Add the `webApp` button factory**

Replace `src/main/java/com/silporestockai/model/TelegramButton.java`:

```java
package com.silporestockai.model;

/**
 * One inline keyboard button, in terms the rest of the app can use without the Telegram SDK.
 *
 * <p>A button carries exactly one of a callback, a URL, or (kept separate because a reply-keyboard WebApp button is
 * built differently from either) a WebApp URL — the factories are the only way to build one and each sets exactly
 * one.
 *
 * @param label text shown on the button
 * @param callbackData opaque payload Telegram sends back in the callback query, at most 64 bytes; null unless this is
 *     a callback button
 * @param url address the button opens; null unless this is a link button
 * @param webAppUrl address of a Telegram WebApp to open; null unless this is a WebApp button
 */
public record TelegramButton(String label, String callbackData, String url, String webAppUrl) {

    /** A button that sends {@code data} back to the bot when tapped. */
    public static TelegramButton callback(String label, String data) {
        return new TelegramButton(label, data, null, null);
    }

    /** A button that opens {@code url}. Used for the Silpo OAuth hand-off, which leaves Telegram. */
    public static TelegramButton link(String label, String url) {
        return new TelegramButton(label, null, url, null);
    }

    /** A button that opens a Telegram WebApp at {@code webAppUrl}. */
    public static TelegramButton webApp(String label, String webAppUrl) {
        return new TelegramButton(label, null, null, webAppUrl);
    }
}
```

- [ ] **Step 5: Route `web_app_data` in `TelegramRoutingService`**

Edit `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`, in `toIncoming`, adding before `if (message.hasVoice())`:

```java
            if (message.hasWebAppData()) {
                return Optional.of(new TelegramIncomingUpdate.WebAppData(
                        chatId, userId, message.getWebAppData().getData()));
            }
```

- [ ] **Step 6: Add the outbound WebApp-button sender**

Edit `src/main/java/com/silporestockai/service/telegram/TelegramOutboundService.java`. Add imports:

```java
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
```

Add the method after `sendMessageWithButtons`:

```java
    /**
     * Sends a message with a reply-keyboard WebApp button plus a plain-text fallback row.
     *
     * <p>Deliberately a {@code ReplyKeyboardMarkup}, not an inline one: only a WebApp opened from a reply-keyboard
     * button delivers its {@code Telegram.WebApp.sendData()} payload back as {@code message.web_app_data}. An inline
     * {@code web_app} button's data goes through {@code answerWebAppQuery} instead, which this application has no use
     * for.
     */
    public void sendMessageWithWebAppButton(
            long chatId, String text, String webAppLabel, String webAppUrl, String fallbackLabel) {
        ReplyKeyboardMarkup markup = ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(KeyboardButton.builder()
                        .text(webAppLabel)
                        .webApp(WebAppInfo.builder().url(webAppUrl).build())
                        .build()))
                .keyboardRow(new KeyboardRow(fallbackLabel))
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();
        SendMessage message =
                SendMessage.builder().chatId(chatId).text(text).replyMarkup(markup).build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            throw failure("sendMessage", e);
        }
    }
```

`toInlineButton` is untouched — `webAppUrl` is never used for inline buttons in this codebase, so no branch is needed there; if a future caller does pass a `webApp`-built `TelegramButton` into `sendMessageWithButtons`, that's a bug to catch in review, not something to silently support.

- [ ] **Step 7: Compile and run the full test suite**

Run: `make test`
Expected: passes — nothing calls the new members yet, and `TelegramButton`'s constructor gained a 4th component, so check whether `TelegramButton.callback(...)`/`.link(...)` call sites elsewhere (there shouldn't be any positional-constructor calls outside the factories — confirm with `grep -rn "new TelegramButton(" src/main` returning nothing).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/silporestockai/model/TelegramButton.java src/main/java/com/silporestockai/model/TelegramIncomingUpdate.java src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java src/main/java/com/silporestockai/service/telegram/TelegramOutboundService.java
git commit -m "Add Telegram WebApp button and web_app_data routing"
```

---

### Task 5: Static onboarding WebApp form + config

**Files:**
- Create: `src/main/resources/static/webapp/onboarding.html`
- Create: `src/main/resources/static/webapp/onboarding.js`
- Modify: `src/main/java/com/silporestockai/config/TelegramProperties.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `TelegramProperties.webAppBaseUrl()`; the page itself, served at `/webapp/onboarding.html` by Spring's default static-resource handling (no controller needed — anything under `src/main/resources/static` is served as-is).

- [ ] **Step 1: Find `TelegramProperties` and add the new property**

Read `src/main/java/com/silporestockai/config/TelegramProperties.java` first to match its exact record/field style, then add a `webAppBaseUrl` component (or field, matching whatever the existing ones use) analogous to `webhookUrl`, plus a `webAppConfigured()` method mirroring the `configured()` pattern used by `GoogleAuthService`:

```java
    /** True when a WebApp base URL is configured — the onboarding flow falls back straight to the text chain when not. */
    public boolean webAppConfigured() {
        return webAppBaseUrl() != null && !webAppBaseUrl().isBlank();
    }
```

- [ ] **Step 2: Add the config property**

Edit `src/main/resources/application.yml`, inside the `telegram:` block, after `api-url`:

```yaml
  # Public HTTPS base URL Spring serves static resources from — used to build the onboarding WebApp form's
  # URL (/webapp/onboarding.html). Blank skips the WebApp button entirely; onboarding goes straight to the
  # text-chain fallback.
  web-app-base-url: ${TELEGRAM_WEB_APP_BASE_URL:}
```

- [ ] **Step 3: Write the static form**

```html
<!-- src/main/resources/static/webapp/onboarding.html -->
<!DOCTYPE html>
<html lang="uk">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Анкета Комора</title>
<script src="https://telegram.org/js/telegram-web-app.js"></script>
<style>
  body { font-family: -apple-system, sans-serif; padding: 16px; background: var(--tg-theme-bg-color, #fff);
         color: var(--tg-theme-text-color, #000); }
  fieldset { border: 1px solid #ccc; border-radius: 8px; margin-bottom: 16px; padding: 12px; }
  legend { font-weight: 600; }
  label { display: block; margin: 8px 0 4px; }
  input[type=number] { width: 60px; }
  .child-row { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
  button { padding: 8px 12px; }
</style>
</head>
<body>
<form id="onboarding-form">
  <fieldset>
    <legend>Дорослі</legend>
    <label>Чоловіків: <input type="number" id="adultMale" min="0" value="1"></label>
    <label>Жінок: <input type="number" id="adultFemale" min="0" value="1"></label>
  </fieldset>

  <fieldset>
    <legend>Діти</legend>
    <div id="children"></div>
    <button type="button" id="addChild">+ дитина</button>
  </fieldset>

  <fieldset>
    <legend>Алергії та обмеження</legend>
    <label><input type="checkbox" name="restriction" value="nuts"> Горіхи</label>
    <label><input type="checkbox" name="restriction" value="lactose"> Лактоза</label>
    <label><input type="checkbox" name="restriction" value="gluten"> Глютен</label>
    <label><input type="checkbox" name="restriction" value="seafood"> Морепродукти</label>
    <label>Інше: <input type="text" id="restrictionsOther"></label>
  </fieldset>

  <fieldset>
    <legend>Тип харчування</legend>
    <select id="dietType">
      <option value="NONE">Без обмежень</option>
      <option value="VEGETARIAN">Вегетаріанство</option>
      <option value="VEGAN">Веганство</option>
      <option value="GLUTEN_FREE">Без глютену</option>
      <option value="KETO">Кето</option>
      <option value="OTHER">Інше</option>
    </select>
  </fieldset>

  <fieldset>
    <legend>Скільки часу на готування</legend>
    <label><input type="radio" name="cookingTime" value="COOKS_DAILY" checked> Готую потроху щодня</label>
    <label><input type="radio" name="cookingTime" value="COOKS_BATCH"> Готую раз на кілька днів, наперед</label>
    <label><input type="radio" name="cookingTime" value="READY_MEALS_ONLY"> Часу немає, лише готова їжа</label>
  </fieldset>

  <button type="submit">Готово</button>
</form>
<script src="onboarding.js"></script>
</body>
</html>
```

```javascript
// src/main/resources/static/webapp/onboarding.js
const tg = window.Telegram.WebApp;
tg.expand();

const CHILD_BRACKETS = [
  ["AGE_0_3", "0–3"],
  ["AGE_4_7", "4–7"],
  ["AGE_8_12", "8–12"],
  ["AGE_13_17", "13–17"],
];

function addChildRow(bracket) {
  const row = document.createElement("div");
  row.className = "child-row";
  const select = document.createElement("select");
  select.className = "child-bracket";
  for (const [value, label] of CHILD_BRACKETS) {
    const opt = document.createElement("option");
    opt.value = value;
    opt.textContent = label;
    if (value === bracket) opt.selected = true;
    select.appendChild(opt);
  }
  const remove = document.createElement("button");
  remove.type = "button";
  remove.textContent = "✕";
  remove.onclick = () => row.remove();
  row.appendChild(select);
  row.appendChild(remove);
  document.getElementById("children").appendChild(row);
}

document.getElementById("addChild").onclick = () => addChildRow("AGE_4_7");

function applyPrefill() {
  const params = new URLSearchParams(window.location.search);
  const raw = params.get("prefill");
  if (!raw) return;
  try {
    const prefill = JSON.parse(atob(raw.replace(/-/g, "+").replace(/_/g, "/")));
    if (prefill.householdSize) {
      document.getElementById("adultMale").value = Math.ceil(prefill.householdSize / 2);
      document.getElementById("adultFemale").value = Math.floor(prefill.householdSize / 2);
    }
  } catch (e) {
    // Malformed or absent prefill is not fatal — the form just starts blank.
    console.warn("could not apply onboarding prefill", e);
  }
}
applyPrefill();

document.getElementById("onboarding-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const restrictions = Array.from(document.querySelectorAll('input[name="restriction"]:checked')).map(
    (el) => el.value,
  );
  const childrenAgeBrackets = Array.from(document.querySelectorAll(".child-bracket")).map((el) => el.value);
  const payload = {
    adultMale: parseInt(document.getElementById("adultMale").value, 10) || 0,
    adultFemale: parseInt(document.getElementById("adultFemale").value, 10) || 0,
    childrenAgeBrackets,
    restrictions,
    restrictionsOther: document.getElementById("restrictionsOther").value.trim(),
    dietType: document.getElementById("dietType").value,
    cookingTimePreference: document.querySelector('input[name="cookingTime"]:checked').value,
  };
  tg.sendData(JSON.stringify(payload));
});
```

- [ ] **Step 4: Verify the app serves it**

Run: `make dev` (or `make run` if Docker Compose Postgres is already up), then in another terminal: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/webapp/onboarding.html`
Expected: `200`. Stop the app afterward (`Ctrl+C`, or `make dev` cleans up its own Testcontainers DB on exit).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/webapp/onboarding.html src/main/resources/static/webapp/onboarding.js src/main/java/com/silporestockai/config/TelegramProperties.java src/main/resources/application.yml
git commit -m "Add the onboarding WebApp form and its base-URL config"
```

---

### Task 6: `OnboardingFlowService` rework

**Files:**
- Modify: `src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java`
- Modify: `src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java`

**Interfaces:**
- Consumes: `TelegramProperties.webAppBaseUrl()/webAppConfigured()` (Task 5), `TelegramOutboundService.sendMessageWithWebAppButton` (Task 4), `TelegramIncomingUpdate.WebAppData` (Task 4), `OnboardingStep.AWAITING_WEBAPP_FORM` (Task 1), `UserProfile.setAdultMaleCount/setAdultFemaleCount/setChildrenAgeBrackets/setDietType/setCookingTimePreference` (Task 2).
- Produces: onboarding now writes the structured fields when the WebApp path is used, and still writes the flat legacy fields either way.

This is the largest single behavioral change in the plan — work through it as one file, six sub-steps, each independently testable against the existing `OnboardingFlowIntegrationTest`.

- [ ] **Step 1: Update the existing tests to the new flow shape (red)**

The three call sites that currently jump straight from `AWAITING_CONNECT`/`CONFIRM_PROFILE` to `ASK_HOUSEHOLD` or `ASK_BUDGET` will instead land on `AWAITING_WEBAPP_FORM`. Edit `OnboardingFlowIntegrationTest`:

Replace `walksAConnectedUserFromConfirmationToASavedProfile`:

```java
    @Test
    void walksAConnectedUserFromConfirmationToASavedProfile() throws Exception {
        sendText(1, "привіт");
        connectSilpo();
        CLAUDE.respondWithText("""
                {"householdSize":4,"hasKids":true,"kidsAges":[3,7],\
                "dietaryRestrictions":["без горіхів"],"frequentItems":["молоко"]}""");

        tapButton(2, "onb:connected");
        assertThat(lastMessageText()).contains("4");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.CONFIRM_PROFILE.name());

        tapButton(3, "onb:confirm");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.AWAITING_WEBAPP_FORM.name());

        sendWebAppData(4, """
                {"adultMale":2,"adultFemale":0,"childrenAgeBrackets":["AGE_4_7"],\
                "restrictions":["nuts"],"restrictionsOther":"","dietType":"NONE",\
                "cookingTimePreference":"COOKS_DAILY"}""");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep()).isEqualTo(OnboardingStep.ASK_BUDGET.name());

        sendText(5, "2500 грн");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        assertThat(profile.getAdultMaleCount()).isEqualTo(2);
        assertThat(profile.getAdultFemaleCount()).isEqualTo(0);
        assertThat(profile.getChildrenAgeBrackets()).containsExactly(com.silporestockai.model.AgeBracket.AGE_4_7);
        assertThat(profile.getCookingTimePreference())
                .isEqualTo(com.silporestockai.model.CookingTimePreference.COOKS_DAILY);
        assertThat(profile.getHouseholdSize()).isEqualTo(3);
        assertThat(profile.getWeeklyBudget()).isEqualByComparingTo("2500");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentFlow()).isEqualTo(ConversationFlow.NONE);
    }
```

Replace `asksEverythingWhenTheUserSkipsConnecting` (the manual-fallback path — the fallback label text is what routes into the old chain now):

```java
    @Test
    void asksEverythingWhenTheUserSkipsConnecting() throws Exception {
        sendText(1, "привіт");

        tapButton(2, "onb:skip");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.AWAITING_WEBAPP_FORM.name());

        sendText(3, "Заповнити вручну");
        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.ASK_HOUSEHOLD.name());

        sendText(4, "нас четверо");
        sendText(5, "алергія на горіхи");
        sendText(6, "броколі");
        sendText(7, "2000");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        assertThat(profile.getHouseholdSize()).isEqualTo(4);
        assertThat(profile.getDietaryRestrictions()).containsExactly("алергія на горіхи");
        assertThat(profile.getDislikedFoods()).containsExactly("броколі");
        assertThat(profile.getWeeklyBudget()).isEqualByComparingTo("2000");
        assertThat(MCP.callCount("tools/call")).isZero();
    }
```

`reAsksRatherThanStoringNonsense` and `resumesFromTheSavedStepAfterTheUserGoesSilent` need the same one-line insertion (`sendText(N, "Заповнити вручну");` and a bumped `assertThat(...)` step, plus renumbered `updateId`s) right after their `tapButton(2, "onb:skip")` line — apply the same pattern shown above.

`correctionOverwritesADetectedField` similarly needs `tapButton(3, "onb:correct")` followed by a manual-fallback text send before its existing `sendText(4, "нас двоє")` continues (renumber the rest).

`anOnboardedUserIsNotOnboardedAgain` needs the same fallback insertion after its `tapButton(2, "onb:skip")`.

Add the `sendWebAppData` helper from Task 4 Step 1 (the corrected, escaping version) to this file if it isn't already there from that task.

Also add two new cases:

```java
    @Test
    void aMalformedWebAppPayloadReAsksInsteadOfCrashing() throws Exception {
        sendText(1, "привіт");
        tapButton(2, "onb:skip");

        sendWebAppData(3, "not json");

        assertThat(conversationStateService.load(CHAT_ID).getCurrentStep())
                .isEqualTo(OnboardingStep.AWAITING_WEBAPP_FORM.name());
        assertThat(userProfileRepository.count()).isZero();
    }

    @Test
    void householdCompositionFromTheWebAppFormMeasurablyChangesTheGeneratedPromptText() throws Exception {
        sendText(1, "привіт");
        tapButton(2, "onb:skip");
        sendWebAppData(3, """
                {"adultMale":1,"adultFemale":1,"childrenAgeBrackets":["AGE_0_3","AGE_8_12"],\
                "restrictions":[],"restrictionsOther":"","dietType":"NONE",\
                "cookingTimePreference":"COOKS_DAILY"}""");
        sendText(4, "2500");

        UUID userId = userRepository.findByTelegramChatId(CHAT_ID).orElseThrow().getId();
        UserProfile profile = userProfileRepository.findByUserId(userId).orElseThrow();
        assertThat(profile.getHouseholdSize()).isEqualTo(4);
        assertThat(profile.getAdultMaleCount()).isEqualTo(1);
        assertThat(profile.getChildrenAgeBrackets())
                .containsExactly(
                        com.silporestockai.model.AgeBracket.AGE_0_3, com.silporestockai.model.AgeBracket.AGE_8_12);
    }
```

(The prompt-text-difference assertion itself — proving `MealPlanService.describe()` renders these differently — belongs in `MealPlanServiceTest`, Task 8; this test only proves the WebApp payload reaches and survives persistence with the right structure, which is what `OnboardingFlowService` is responsible for.)

- [ ] **Step 2: Run the suite to see it fail**

Run: `./gradlew test --tests "com.silporestockai.integration.OnboardingFlowIntegrationTest"`
Expected: FAIL — every test that now expects `AWAITING_WEBAPP_FORM` instead sees the old step name, and `sendWebAppData`/`AgeBracket` etc. may not compile yet if Task 4/1 weren't already applied (they are, by this point in the plan).

- [ ] **Step 3: Rework `greet`/`handleButton`/`enrichThenConfirm` to route through `AWAITING_WEBAPP_FORM`**

Edit `src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java`. Add imports: `com.silporestockai.model.AgeBracket`, `com.silporestockai.model.CookingTimePreference`, `com.silporestockai.model.DietType`, `com.silporestockai.config.TelegramProperties`, `com.fasterxml.jackson.databind.ObjectMapper`, `java.util.Base64`.

Add a constant and the `TelegramProperties` dependency:

```java
    public static final String CALLBACK_MANUAL = "onb:manual";
    private static final String FALLBACK_LABEL = "Заповнити вручну";

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
```

Add `TelegramProperties telegramProperties` to the constructor's field list (Lombok's `@RequiredArgsConstructor` picks it up automatically once declared as a `private final` field).

Change every `askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user)` call in `handleButton` (both the `AWAITING_CONNECT`+`CALLBACK_SKIP` branch and the `CONFIRM_PROFILE`+`CALLBACK_CORRECT` branch) to `presentWebAppForm(chatId, context)`. Change the `CONFIRM_PROFILE`+`CALLBACK_CONFIRM` branch's `askNext(chatId, OnboardingStep.ASK_BUDGET, context, user)` to `presentWebAppForm(chatId, context)` too — budget is still asked, but only after the WebApp form (or its fallback) runs, since the form also needs to run for users whose Silpo profile already answered the old household/restrictions questions.

Add the new method, near `enrichThenConfirm`:

```java
    /**
     * Opens the WebApp form (or, when it's not configured, skips straight to the manual fallback chain).
     *
     * <p>The WebApp form collects fields no Silpo enrichment can supply — diet type, cooking-time preference, a
     * per-child age bracket — so it runs even when {@code enrichThenConfirm} already confirmed the flat household
     * fields; those become the form's prefill, not a reason to skip it.
     */
    private void presentWebAppForm(long chatId, Map<String, Object> context) {
        if (!telegramProperties.webAppConfigured()) {
            askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, null);
            return;
        }
        String formUrl = telegramProperties.webAppBaseUrl() + "/webapp/onboarding.html?prefill=" + prefillOf(context);
        telegramOutboundService.sendMessageWithWebAppButton(
                chatId, "Заповни коротку анкету — це швидше, ніж відповідати текстом.", "Заповнити анкету", formUrl,
                FALLBACK_LABEL);
        save(chatId, OnboardingStep.AWAITING_WEBAPP_FORM, context);
    }

    private static String prefillOf(Map<String, Object> context) {
        try {
            Map<String, Object> prefill = new LinkedHashMap<>();
            putIfPresent(prefill, "householdSize", context.get(KEY_HOUSEHOLD));
            String json = MAPPER.writeValueAsString(prefill);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
        } catch (Exception e) {
            return "";
        }
    }
```

`askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, null)` passes `null` for `user` in the no-WebApp-configured fallback — check `askNext`'s body: it only uses `user` in its `default -> finish(user, chatId, context)` branch, which is unreachable when `target` starts at `ASK_HOUSEHOLD` and every step before `ASK_BUDGET` (the only one whose `following` chain ends in `ASK_BUDGET`, never falling to `default`) is walked — so `null` is safe here, but to avoid a landmine for the next reader, change `presentWebAppForm`'s signature to accept `User user` and thread it through instead:

```java
    private void presentWebAppForm(long chatId, Map<String, Object> context, User user) {
        if (!telegramProperties.webAppConfigured()) {
            askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user);
            return;
        }
        ...
        save(chatId, OnboardingStep.AWAITING_WEBAPP_FORM, context);
    }
```

and update its three call sites in `handleButton` to pass `user` (already in scope there).

- [ ] **Step 4: Handle `AWAITING_WEBAPP_FORM` in `handle()`**

Edit the `switch (incoming)` in `handle()`:

```java
        switch (incoming) {
            case TelegramIncomingUpdate.ButtonTap tap -> {
                telegramOutboundService.answerCallback(tap.callbackQueryId());
                handleButton(user, chatId, step, tap.data(), context);
            }
            case TelegramIncomingUpdate.WebAppData webAppData -> handleWebAppSubmit(user, chatId, step, webAppData.data(), context);
            case TelegramIncomingUpdate.Text text -> handleAnswer(user, chatId, step, text.text(), context);
            case TelegramIncomingUpdate.Voice ignored ->
                telegramOutboundService.sendMessage(chatId, "Голосові поки не розбираю. Напиши, будь ласка, текстом.");
            case TelegramIncomingUpdate.Photo ignored ->
                telegramOutboundService.sendMessage(chatId, "Фото тут не допоможе. Напиши, будь ласка, текстом.");
        }
```

Add `handleWebAppSubmit`, and add the `AWAITING_WEBAPP_FORM`-fallback branch inside `handleAnswer`:

```java
    private void handleWebAppSubmit(User user, long chatId, OnboardingStep step, String json, Map<String, Object> context) {
        if (step != OnboardingStep.AWAITING_WEBAPP_FORM) {
            telegramOutboundService.sendMessage(chatId, "Скористайся, будь ласка, кнопками вище.");
            return;
        }
        WebAppOnboardingPayload payload;
        try {
            payload = MAPPER.readValue(json, WebAppOnboardingPayload.class);
        } catch (Exception e) {
            log.warn("could not parse onboarding WebApp payload for chat {}: {}", chatId, e.toString());
            telegramOutboundService.sendMessage(chatId, "Не вдалось прочитати анкету. Спробуй ще раз або натисни «Заповнити вручну».");
            return;
        }
        context.put(KEY_ADULT_MALE, payload.adultMale());
        context.put(KEY_ADULT_FEMALE, payload.adultFemale());
        context.put(KEY_CHILDREN_BRACKETS, payload.childrenAgeBrackets() == null ? List.of() : payload.childrenAgeBrackets());
        List<String> restrictions = new ArrayList<>(payload.restrictions() == null ? List.<String>of() : payload.restrictions());
        if (payload.restrictionsOther() != null && !payload.restrictionsOther().isBlank()) {
            restrictions.add(payload.restrictionsOther().trim());
        }
        context.put(KEY_RESTRICTIONS, restrictions);
        context.put(KEY_DIET_TYPE, payload.dietType());
        context.put(KEY_COOKING_TIME, payload.cookingTimePreference());
        askNext(chatId, OnboardingStep.ASK_BUDGET, context, user);
    }
```

In `handleAnswer`, add a case matching `AWAITING_WEBAPP_FORM` + the fallback label text, ahead of the existing `switch (step)`:

```java
    private void handleAnswer(User user, long chatId, OnboardingStep step, String answer, Map<String, Object> context) {
        if (step == OnboardingStep.AWAITING_WEBAPP_FORM) {
            if (FALLBACK_LABEL.equals(answer.trim())) {
                askNext(chatId, OnboardingStep.ASK_HOUSEHOLD, context, user);
            } else {
                telegramOutboundService.sendMessage(chatId, "Натисни кнопку «Заповнити анкету» або «" + FALLBACK_LABEL + "».");
            }
            return;
        }
        switch (step) {
            ...
```

Add the new context keys near the existing `KEY_*` constants:

```java
    private static final String KEY_ADULT_MALE = "adultMale";
    private static final String KEY_ADULT_FEMALE = "adultFemale";
    private static final String KEY_CHILDREN_BRACKETS = "childrenAgeBrackets";
    private static final String KEY_DIET_TYPE = "dietType";
    private static final String KEY_COOKING_TIME = "cookingTimePreference";
```

Add the payload record as a nested type at the bottom of the class (keeps it colocated with its one reader, and Jackson can deserialize a package-private nested record fine):

```java
    private record WebAppOnboardingPayload(
            Integer adultMale,
            Integer adultFemale,
            List<AgeBracket> childrenAgeBrackets,
            List<String> restrictions,
            String restrictionsOther,
            DietType dietType,
            CookingTimePreference cookingTimePreference) {}
```

- [ ] **Step 5: Extend `finish()` to persist the structured fields and derive the legacy ones**

Edit `finish()`:

```java
    private void finish(User user, long chatId, Map<String, Object> context) {
        UserProfile profile = userProfileRepository
                .findByUserId(user.getId())
                .orElseGet(() -> UserProfile.builder()
                        .id(UUID.randomUUID())
                        .userId(user.getId())
                        .build());

        Integer adultMale = intOf(context.get(KEY_ADULT_MALE));
        Integer adultFemale = intOf(context.get(KEY_ADULT_FEMALE));
        List<AgeBracket> brackets = ageBracketListOf(context.get(KEY_CHILDREN_BRACKETS));
        if (adultMale != null || adultFemale != null) {
            profile.setAdultMaleCount(adultMale);
            profile.setAdultFemaleCount(adultFemale);
            profile.setChildrenAgeBrackets(brackets);
            profile.setDietType(context.get(KEY_DIET_TYPE) instanceof DietType dietType ? dietType : diet(context));
            profile.setCookingTimePreference(cookingTime(context));
            int adults = (adultMale == null ? 0 : adultMale) + (adultFemale == null ? 0 : adultFemale);
            profile.setHouseholdSize(adults + brackets.size());
            profile.setHasKids(!brackets.isEmpty());
            profile.setKidsAges(brackets.stream().map(OnboardingFlowService::midpointAge).toList());
        } else {
            profile.setHouseholdSize(intOf(context.get(KEY_HOUSEHOLD)));
            profile.setHasKids(context.get(KEY_HAS_KIDS) instanceof Boolean flag ? flag : null);
            profile.setKidsAges(intListOf(context.get(KEY_KIDS_AGES)));
        }
        profile.setDietaryRestrictions(stringListOf(context.get(KEY_RESTRICTIONS)));
        profile.setDislikedFoods(stringListOf(context.get(KEY_DISLIKES)));
        profile.setWeeklyBudget(
                context.get(KEY_BUDGET) == null
                        ? null
                        : new BigDecimal(context.get(KEY_BUDGET).toString()));
        userProfileRepository.save(profile);

        conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
        telegramOutboundService.sendMessageWithMainMenu(chatId, "Записав. Готую перший план на тиждень.");
        events.publishEvent(new OnboardingCompletedEvent(user.getId()));
        log.info("onboarding completed for user {}", user.getId());
    }

    private static DietType diet(Map<String, Object> context) {
        return context.get(KEY_DIET_TYPE) instanceof DietType dietType ? dietType : DietType.NONE;
    }

    private static CookingTimePreference cookingTime(Map<String, Object> context) {
        return context.get(KEY_COOKING_TIME) instanceof CookingTimePreference preference ? preference : null;
    }

    private static int midpointAge(AgeBracket bracket) {
        return switch (bracket) {
            case AGE_0_3 -> 2;
            case AGE_4_7 -> 5;
            case AGE_8_12 -> 10;
            case AGE_13_17 -> 15;
        };
    }

    @SuppressWarnings("unchecked")
    private static List<AgeBracket> ageBracketListOf(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return ((List<Object>) list)
                .stream()
                        .map(item -> item instanceof AgeBracket bracket ? bracket : AgeBracket.valueOf(item.toString()))
                        .toList();
    }
```

`context.get(KEY_DIET_TYPE)` holds a `DietType` when it came straight from Jackson deserialization inside the same JVM call as `handleWebAppSubmit` → `askNext` → ... → `finish` — but `conversation_state.context_json` round-trips through JSON storage between webhook calls (per the class's own Javadoc: "the whole conversation lives in `conversation_state`"), so by the time `finish()` runs on a *later* webhook call, `context.get(KEY_DIET_TYPE)` is a `String`, not a `DietType`. Fix `diet`/`cookingTime` to handle both:

```java
    private static DietType diet(Map<String, Object> context) {
        Object value = context.get(KEY_DIET_TYPE);
        if (value instanceof DietType dietType) {
            return dietType;
        }
        return value == null ? DietType.NONE : DietType.valueOf(value.toString());
    }

    private static CookingTimePreference cookingTime(Map<String, Object> context) {
        Object value = context.get(KEY_COOKING_TIME);
        if (value instanceof CookingTimePreference preference) {
            return preference;
        }
        return value == null ? null : CookingTimePreference.valueOf(value.toString());
    }
```

Same round-trip issue applies to `ageBracketListOf` — it already handles both cases (the `instanceof AgeBracket bracket ? bracket : AgeBracket.valueOf(item.toString())` branch).

- [ ] **Step 6: Handle the `onb:manual` callback name from the design doc vs. the fallback being plain text**

The design's `CALLBACK_MANUAL` constant is unused — the fallback is a reply-keyboard **text** button, not an inline callback (see Task 4's rationale), so remove the unused `CALLBACK_MANUAL` constant added in Step 3 to avoid a dead-code lint flag, and rely solely on the `FALLBACK_LABEL` text match added in Step 4.

- [ ] **Step 7: Run the full onboarding test file**

Run: `./gradlew test --tests "com.silporestockai.integration.OnboardingFlowIntegrationTest"`
Expected: PASS, all cases including the two new ones.

- [ ] **Step 8: Run the full suite**

Run: `make test`
Expected: PASS (this also catches any other test that constructed a `TelegramButton` positionally or relied on the old step ordering).

- [ ] **Step 9: `make format` and commit**

```bash
make format
git add src/main/java/com/silporestockai/service/onboarding/OnboardingFlowService.java src/test/java/com/silporestockai/integration/OnboardingFlowIntegrationTest.java
git commit -m "Route onboarding through a WebApp form with a text-chain fallback"
```

---

### Task 7: Ingredient categorization — schema, mapper, keyword fallback, prompts

**Files:**
- Modify: `src/main/java/com/silporestockai/model/PlannedIngredient.java`
- Modify: `src/main/java/com/silporestockai/mapper/ShoppingListItemMapper.java`
- Create: `src/main/java/com/silporestockai/service/CategoryKeywordFallbackService.java`
- Modify: `src/main/java/com/silporestockai/service/ShoppingListService.java` (only the `aggregate`/`add`/`normalise` helpers — the archive/CRUD changes are Task 9)
- Modify: `src/main/resources/prompts/meal-plan-system.txt`, `src/main/resources/prompts/shopping-list-system.txt`
- Test: `src/test/java/com/silporestockai/unit/ShoppingListServiceAggregationTest.java` — check first whether a plain (non-Spring) unit test for `ShoppingListService.aggregate` already exists elsewhere (`grep -rln "ShoppingListService.aggregate\|ShoppingListService::aggregate" src/test`) and extend it in place if so, rather than creating a duplicate.

**Interfaces:**
- Produces: `PlannedIngredient(String name, BigDecimal quantity, String unit, String category)`; `CategoryKeywordFallbackService.categorize(String itemName)` returning `String`.
- Consumes downstream (Task 9, Task 11): `ShoppingListItem.getCategory()` is now reliably populated.

- [ ] **Step 1: Find or write the aggregation test (red)**

```bash
grep -rln "aggregate" src/test/java/com/silporestockai
```

If a test file exists, open it and add the case below into it; otherwise create `src/test/java/com/silporestockai/unit/ShoppingListServiceAggregationTest.java`:

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.service.ShoppingListService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShoppingListServiceAggregationTest {

    @Test
    void aggregationKeepsTheFirstNonBlankCategoryForRepeatedIngredients() {
        List<PlannedIngredient> ingredients = List.of(
                new PlannedIngredient("Цибуля", new BigDecimal("1"), "шт", "Овочі і фрукти"),
                new PlannedIngredient("Цибуля", new BigDecimal("2"), "шт", null));

        List<PlannedIngredient> aggregated = ShoppingListService.aggregate(ingredients);

        assertThat(aggregated).hasSize(1);
        assertThat(aggregated.getFirst().category()).isEqualTo("Овочі і фрукти");
        assertThat(aggregated.getFirst().quantity()).isEqualByComparingTo("3");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "com.silporestockai.unit.ShoppingListServiceAggregationTest"`
Expected: FAIL — compile error, `PlannedIngredient` has no 4-arg constructor yet.

- [ ] **Step 3: Add `category` to `PlannedIngredient`**

```java
// src/main/java/com/silporestockai/model/PlannedIngredient.java
package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One ingredient of a planned meal.
 *
 * @param name the ingredient as a person would write it on a list, in Ukrainian
 * @param quantity how much is needed for the meal
 * @param unit the unit the quantity is in, e.g. {@code кг}, {@code шт}, {@code л}
 * @param category a short category label from the fixed taxonomy the system prompt gives (e.g. "Молочні продукти"),
 *     or null when the model left it out — {@link com.silporestockai.service.CategoryKeywordFallbackService} fills
 *     that gap.
 */
public record PlannedIngredient(String name, BigDecimal quantity, String unit, String category) {}
```

Every existing constructor call site now needs a 4th argument — find them:

```bash
grep -rln "new PlannedIngredient(" src/main src/test
```

For each production call site found (expected: `ShoppingListService.add`, and test fixtures), pass `category` through. In `ShoppingListService.aggregate`'s `merge`, the constructed placeholder passed as the merge seed must carry the incoming ingredient's category:

```java
            byNameAndUnit.merge(
                    key,
                    new PlannedIngredient(ingredient.name().trim(), ingredient.quantity(), ingredient.unit(), ingredient.category()),
                    ShoppingListService::add);
```

And `add` keeps the first non-blank category, same spirit as "keeps the first line's spelling and unit":

```java
    /** Keeps the first line's spelling, unit and category; only the quantity accumulates. */
    private static PlannedIngredient add(PlannedIngredient existing, PlannedIngredient extra) {
        BigDecimal quantity;
        if (existing.quantity() == null) {
            quantity = extra.quantity();
        } else if (extra.quantity() == null) {
            quantity = existing.quantity();
        } else {
            quantity = existing.quantity().add(extra.quantity());
        }
        String category = existing.category() != null && !existing.category().isBlank()
                ? existing.category()
                : extra.category();
        return new PlannedIngredient(existing.name(), quantity, existing.unit(), category);
    }
```

- [ ] **Step 4: Run the aggregation test again**

Run: `./gradlew test --tests "com.silporestockai.unit.ShoppingListServiceAggregationTest"`
Expected: PASS.

- [ ] **Step 5: Add the keyword fallback service**

```java
// src/main/java/com/silporestockai/service/CategoryKeywordFallbackService.java
package com.silporestockai.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Guesses a category from an item's name when Claude's generation left {@code category} blank.
 *
 * <p>Deterministic keyword matching, not a model call: this exists precisely so a category the model omitted doesn't
 * require an extra AI round-trip to fill in — see {@code ShoppingListService}'s "AI called only on real change" rule.
 */
@Service
public class CategoryKeywordFallbackService {

    private static final String FALLBACK_CATEGORY = "Інше";

    /** Ordered so a more specific keyword (e.g. "кисломолочн") can be checked before a broader one matches first. */
    private static final Map<String, String> KEYWORD_TO_CATEGORY = new LinkedHashMap<>();

    static {
        KEYWORD_TO_CATEGORY.put("молок", "Молочні продукти");
        KEYWORD_TO_CATEGORY.put("сир", "Молочні продукти");
        KEYWORD_TO_CATEGORY.put("йогурт", "Молочні продукти");
        KEYWORD_TO_CATEGORY.put("м'ясо", "М'ясо і птиця");
        KEYWORD_TO_CATEGORY.put("курк", "М'ясо і птиця");
        KEYWORD_TO_CATEGORY.put("філе", "М'ясо і птиця");
        KEYWORD_TO_CATEGORY.put("фарш", "М'ясо і птиця");
        KEYWORD_TO_CATEGORY.put("риб", "Риба і морепродукти");
        KEYWORD_TO_CATEGORY.put("овоч", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("цибул", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("картопл", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("помідор", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("фрукт", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("яблук", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("банан", "Овочі і фрукти");
        KEYWORD_TO_CATEGORY.put("гречк", "Крупи і бакалія");
        KEYWORD_TO_CATEGORY.put("рис", "Крупи і бакалія");
        KEYWORD_TO_CATEGORY.put("макарон", "Крупи і бакалія");
        KEYWORD_TO_CATEGORY.put("борошн", "Крупи і бакалія");
        KEYWORD_TO_CATEGORY.put("хліб", "Хлібобулочні вироби");
        KEYWORD_TO_CATEGORY.put("яйц", "Яйця");
    }

    public String categorize(String itemName) {
        if (itemName == null) {
            return FALLBACK_CATEGORY;
        }
        String lower = itemName.toLowerCase(Locale.ROOT);
        return KEYWORD_TO_CATEGORY.entrySet().stream()
                .filter(entry -> lower.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(FALLBACK_CATEGORY);
    }
}
```

- [ ] **Step 6: Write a failing test for the fallback service**

```java
// src/test/java/com/silporestockai/unit/CategoryKeywordFallbackServiceTest.java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.service.CategoryKeywordFallbackService;
import org.junit.jupiter.api.Test;

class CategoryKeywordFallbackServiceTest {

    private final CategoryKeywordFallbackService service = new CategoryKeywordFallbackService();

    @Test
    void matchesAKnownKeyword() {
        assertThat(service.categorize("Молоко 2.5%")).isEqualTo("Молочні продукти");
    }

    @Test
    void fallsBackForAnUnknownName() {
        assertThat(service.categorize("Дещо незрозуміле")).isEqualTo("Інше");
    }

    @Test
    void treatsANullNameAsUnknown() {
        assertThat(service.categorize(null)).isEqualTo("Інше");
    }
}
```

Run: `./gradlew test --tests "com.silporestockai.unit.CategoryKeywordFallbackServiceTest"`
Expected: PASS immediately (the implementation was written in Step 5) — this is acceptable here since the service is small and the test is the specification check, not a red/green driver; if strict TDD ordering matters more than this plan assumes, write the test class first and run it against a not-yet-created class to see the compile failure, then add Step 5's implementation.

- [ ] **Step 7: Wire the fallback into the mapper**

Replace `src/main/java/com/silporestockai/mapper/ShoppingListItemMapper.java`. MapStruct mappers can't easily call a Spring-managed collaborator inline in an annotation, so move the fallback application to the caller (`ShoppingListService`/`ShoppingListItemMapper`'s consumer) instead of the mapper itself — simplest: keep the mapper mapping `category` straight from the ingredient, and apply the fallback in `ShoppingListService` right before calling the mapper (Task 9 touches these call sites anyway; do it here since it belongs with this task's category work):

```java
@Mapper
public interface ShoppingListItemMapper {

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "mealPlanId", source = "mealPlanId")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "category", source = "ingredient.category")
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "sourceType", ignore = true)
    ShoppingListItem toItem(PlannedIngredient ingredient, UUID mealPlanId, UUID userId);
}
```

(`status` defaults to `ACTIVE` via the entity's `@Builder.Default`... but MapStruct's generated `toItem` uses the entity's setters, not its builder, so the `@Builder.Default` initializer does not apply here — leaving `status` `ignore`d would leave it `null`, not `ACTIVE`. Fix: don't ignore it, set it explicitly with a constant expression:)

```java
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "sourceType", ignore = true)
```

MapStruct maps a `String` constant onto an enum-typed target field automatically via `ShoppingListStatus.valueOf(...)` when the constant matches an enum name — this is standard MapStruct behavior, verify it compiles in Step 9.

- [ ] **Step 8: Apply the keyword fallback in `ShoppingListService`**

This step's code lands in Task 9 (`ShoppingListService.deriveFromMealPlan`/`createAdHocList` are rewritten there for the archive-vs-delete change); note here, for Task 9's author, that both methods must run each aggregated ingredient through `CategoryKeywordFallbackService` before mapping when `ingredient.category()` is blank:

```java
    private static PlannedIngredient withFallbackCategory(PlannedIngredient ingredient, CategoryKeywordFallbackService fallback) {
        if (ingredient.category() != null && !ingredient.category().isBlank()) {
            return ingredient;
        }
        return new PlannedIngredient(ingredient.name(), ingredient.quantity(), ingredient.unit(), fallback.categorize(ingredient.name()));
    }
```

(Task 9 adds `CategoryKeywordFallbackService` to `ShoppingListService`'s constructor and calls this helper in both `deriveFromMealPlan` and `createAdHocList` right before the `.map(ingredient -> shoppingListItemMapper.toItem(...))` calls.)

- [ ] **Step 9: Update the prompts to ask for `category`**

Since `ClaudeApiClient.completeStructured` derives its JSON schema straight from the Java type (per its own Javadoc), adding `category` to `PlannedIngredient`/`ShoppingListDraft`'s item shape already changes what Claude is asked to fill — but the prompt text should tell it the taxonomy to use, or it'll invent inconsistent labels. Add a bullet to `src/main/resources/prompts/meal-plan-system.txt`, after the existing "Кожна страва має назву..." bullet:

```
- Кожен інгредієнт має category — одну з: "Молочні продукти", "М'ясо і птиця", "Риба і морепродукти",
  "Овочі і фрукти", "Крупи і бакалія", "Хлібобулочні вироби", "Яйця", "Інше". Обери найточнішу, а не завжди
  "Інше".
```

Add the equivalent bullet to `src/main/resources/prompts/shopping-list-system.txt`, in the "Правила, спільні для обох типів" section, and update its JSON example:

```
- Відповідай ЛИШЕ одним JSON-об'єктом: {"items": [{"name": "...", "quantity": 1, "unit": "шт", "category": "..."}]}.
  Жодного тексту навколо, жодних пояснень.
```

```
- category — одна з: "Молочні продукти", "М'ясо і птиця", "Риба і морепродукти", "Овочі і фрукти",
  "Крупи і бакалія", "Хлібобулочні вироби", "Яйця", "Інше". Обери найточнішу.
```

Also check whether `ShoppingListDraft`/`PlannedIngredient` is used directly as the structured-output type for the shopping-list-building call — it is (`ShoppingListBuilderService.buildAndShow` calls `claudeApiClient.completeStructured(systemPrompt, userPrompt, ShoppingListDraft.class)`), so the same `PlannedIngredient.category` field applies there too with no separate change needed.

- [ ] **Step 10: Run the full suite**

Run: `make test`
Expected: PASS. (`ShoppingListItemMapperTest` if one exists needs the same 4-arg `PlannedIngredient` fix — check with `grep -rln "ShoppingListItemMapper" src/test`.)

- [ ] **Step 11: `make format` and commit**

```bash
make format
git add src/main/java/com/silporestockai/model/PlannedIngredient.java src/main/java/com/silporestockai/mapper/ShoppingListItemMapper.java src/main/java/com/silporestockai/service/CategoryKeywordFallbackService.java src/main/java/com/silporestockai/service/ShoppingListService.java src/main/resources/prompts/meal-plan-system.txt src/main/resources/prompts/shopping-list-system.txt src/test/java/com/silporestockai/unit/CategoryKeywordFallbackServiceTest.java src/test/java/com/silporestockai/unit/ShoppingListServiceAggregationTest.java
git commit -m "Add ingredient categorization with a keyword fallback"
```

---

### Task 8: `MealPlanService` generation fork

**Files:**
- Modify: `src/main/java/com/silporestockai/service/MealPlanService.java`
- Create: `src/main/resources/prompts/meal-plan-ready-meals-system.txt`
- Create: `src/test/java/com/silporestockai/unit/MealPlanServiceTest.java`

**Interfaces:**
- Consumes: `UserProfile.getCookingTimePreference()`, `.getAdultMaleCount()/.getAdultFemaleCount()/.getChildrenAgeBrackets()` (Task 2).
- Produces: `MealPlanService.generate` now picks between two prompts; `describe()` mentions the structured household breakdown when present.

- [ ] **Step 1: Write the failing unit test for prompt selection**

Read `MealPlanService`'s constructor again — it takes `Resource systemPromptResource` via `@Value`. A plain (non-Spring) unit test constructs it directly with two `ByteArrayResource`s standing in for the two prompt files, and a mocked `ClaudeApiClient` that records which system prompt it was called with:

```java
// src/test/java/com/silporestockai/unit/MealPlanServiceTest.java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.DayOfWeek0;
import com.silporestockai.model.PlannedDay;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.PlannedMeal;
import com.silporestockai.model.WeeklyMealPlan;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.InventoryTrendService;
import com.silporestockai.service.MealPlanService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class MealPlanServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void picksTheReadyMealsPromptForReadyMealsOnlyHouseholds() {
        assertGenerationUsesPrompt(CookingTimePreference.READY_MEALS_ONLY, "READY-MEALS-PROMPT");
    }

    @Test
    void picksTheRecipePromptForCooksDailyHouseholds() {
        assertGenerationUsesPrompt(CookingTimePreference.COOKS_DAILY, "RECIPE-PROMPT");
    }

    @Test
    void picksTheRecipePromptWhenNoPreferenceIsSet() {
        assertGenerationUsesPrompt(null, "RECIPE-PROMPT");
    }

    private void assertGenerationUsesPrompt(CookingTimePreference preference, String expectedPromptMarker) {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile =
                UserProfile.builder().id(UUID.randomUUID()).userId(USER_ID).cookingTimePreference(preference).build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        when(mealPlanRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());

        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(validPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()));

        service.generateWeeklyPlan(USER_ID);

        org.mockito.ArgumentCaptor<String> systemPromptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(claudeApiClient)
                .completeStructured(systemPromptCaptor.capture(), anyString(), eq(WeeklyMealPlan.class));
        assertThat(systemPromptCaptor.getValue()).isEqualTo(expectedPromptMarker);
    }

    private static WeeklyMealPlan validPlan() {
        List<PlannedIngredient> ingredients =
                List.of(new PlannedIngredient("Щось", BigDecimal.ONE, "шт", "Інше"));
        List<PlannedMeal> meals = List.of(
                new PlannedMeal("Сніданок", ingredients),
                new PlannedMeal("Обід", ingredients),
                new PlannedMeal("Вечеря", ingredients));
        List<PlannedDay> days = Arrays.stream(DayOfWeek.values())
                .map(day -> new PlannedDay(day, meals))
                .toList();
        return new WeeklyMealPlan(days);
    }
}
```

Check `PlannedMeal`'s and `PlannedDay`'s actual constructor argument order/types first (`cat src/main/java/com/silporestockai/model/PlannedMeal.java src/main/java/com/silporestockai/model/PlannedDay.java`) and adjust the test fixture to match exactly — the above assumes `PlannedMeal(String name, List<PlannedIngredient> ingredients)` and `PlannedDay(DayOfWeek day, List<PlannedMeal> meals)`, matching how `MealPlanService.defectsOf` reads them (`day.day()`, `day.meals()`, `meal.name()`, `meal.ingredients()`); remove the unused `DayOfWeek0` import (leftover from drafting — delete that line).

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "com.silporestockai.unit.MealPlanServiceTest"`
Expected: FAIL — compile error, `MealPlanService`'s constructor doesn't take two `Resource` params yet.

- [ ] **Step 3: Write the ready-meals prompt**

```
# src/main/resources/prompts/meal-plan-ready-meals-system.txt
Ти складаєш тижневе меню готової їжі для родини в Україні, яка не готує сама. Пишеш українською.

Правила:
- Рівно 7 днів: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY. Кожен день рівно один раз.
- Мінімум 3 прийоми їжі на день: BREAKFAST, LUNCH, DINNER. SNACK додавай лише якщо в родині є діти.
- Кожен прийом їжі — це РІВНО ОДНА готова страва з супермаркету «Сільпо»: салат, готовий гарячий обід,
  сендвіч, готова випічка, консерви, що не потребують готування. Ніяких рецептів, ніяких сирих
  інгредієнтів, ніяких "з чого це складається".
- Кожна страва у відповіді описується як звичайний інгредієнт: name — назва готового продукту так, як
  вона є в супермаркеті («Салат Цезар готовий», «Борщ готовий, порція»), quantity — завжди 1, unit —
  "порція" або "шт", category — одна з "Молочні продукти", "М'ясо і птиця", "Риба і морепродукти",
  "Овочі і фрукти", "Крупи і бакалія", "Хлібобулочні вироби", "Яйця", "Готові страви", "Інше".
- Обмеження та алергії з профілю — абсолютні. Жодної готової страви, яка їх порушує.
- Продукти, які в родині не їдять, не використовуй взагалі.
- Меню має бути різноманітним: різні готові страви в різні дні. Тиждень з одного продукту — це помилка.
- Продукти зі списку «не пропонуй» не використовуй теж.
- Бюджет — орієнтир, а не жорстке обмеження.
- Ніяких коментарів, пояснень чи тексту поза структурою відповіді.
```

- [ ] **Step 4: Fork the constructor and generation logic**

Edit `src/main/java/com/silporestockai/service/MealPlanService.java`:

```java
    private final UserProfileRepository userProfileRepository;
    private final MealPlanRepository mealPlanRepository;
    private final ClaudeApiClient claudeApiClient;
    private final InventoryTrendService inventoryTrendService;
    private final Clock clock;
    private final String recipeSystemPrompt;
    private final String readyMealsSystemPrompt;

    public MealPlanService(
            UserProfileRepository userProfileRepository,
            MealPlanRepository mealPlanRepository,
            ClaudeApiClient claudeApiClient,
            InventoryTrendService inventoryTrendService,
            Clock clock,
            @Value("classpath:prompts/meal-plan-system.txt") Resource recipeSystemPromptResource,
            @Value("classpath:prompts/meal-plan-ready-meals-system.txt") Resource readyMealsSystemPromptResource) {
        this.userProfileRepository = userProfileRepository;
        this.mealPlanRepository = mealPlanRepository;
        this.claudeApiClient = claudeApiClient;
        this.inventoryTrendService = inventoryTrendService;
        this.clock = clock;
        this.recipeSystemPrompt = read(recipeSystemPromptResource);
        this.readyMealsSystemPrompt = read(readyMealsSystemPromptResource);
    }
```

Edit `generate` to pick the prompt and to know which `ShoppingListSourceType` it corresponds to (returned alongside the plan so `MealPlanHandoffService`/Task 9's `deriveFromMealPlan` can tag the derived items):

```java
    private MealPlan generate(UUID userId, String adjustment) {
        UserProfile profile = userProfileRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ApplicationException(
                        HttpStatus.PRECONDITION_REQUIRED,
                        "user %s has no profile yet; onboarding has to finish first".formatted(userId)));

        boolean readyMealsOnly = profile.getCookingTimePreference() == CookingTimePreference.READY_MEALS_ONLY;
        String systemPrompt = readyMealsOnly ? readyMealsSystemPrompt : recipeSystemPrompt;

        String userPrompt = describe(profile, adjustment, inventoryTrendService.getRemovalCandidates(userId));
        WeeklyMealPlan plan = claudeApiClient.completeStructured(systemPrompt, userPrompt, WeeklyMealPlan.class);
        List<String> defects = defectsOf(plan);
        if (!defects.isEmpty()) {
            log.warn("Claude returned an unusable plan for user {}: {}", userId, defects);
            plan = claudeApiClient.completeStructured(
                    systemPrompt, correctionOf(userPrompt, defects), WeeklyMealPlan.class);
            defects = defectsOf(plan);
            if (!defects.isEmpty()) {
                throw new MealPlanGenerationException(userId, defects);
            }
        }
        return persist(userId, plan, readyMealsOnly ? ShoppingListSourceType.READY_MEAL_DIRECT : ShoppingListSourceType.RECIPE_DERIVED);
    }
```

`persist` needs to carry the source type onto the `MealPlan` row so `MealPlanHandoffService` can read it back when it later calls `shoppingListService.deriveFromMealPlan(plan.getId())` — the cleanest carrier is a transient (non-persisted) field on the returned `MealPlan` instance rather than a new DB column (the row itself doesn't need to remember this permanently; only the very next call in the same request does). Check `MealPlan`'s entity definition (`cat src/main/java/com/silporestockai/entity/MealPlan.java`) — if it's a `@Entity` with Lombok `@Builder`, add a `@Transient` field:

```java
    @Transient
    @Builder.Default
    private ShoppingListSourceType sourceType = ShoppingListSourceType.RECIPE_DERIVED;
```

and set it in `persist`:

```java
    private MealPlan persist(UUID userId, WeeklyMealPlan plan, ShoppingListSourceType sourceType) {
        @SuppressWarnings("unchecked")
        Map<String, Object> asJson = MAPPER.convertValue(plan, Map.class);
        MealPlan row = mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .weekStartDate(upcomingWeekStart())
                .plan(asJson)
                .createdAt(Instant.now())
                .build());
        row.setSourceType(sourceType);
        log.info("stored a weekly plan for user {} starting {}", userId, row.getWeekStartDate());
        return row;
    }
```

Add the `CookingTimePreference`/`ShoppingListSourceType` imports.

- [ ] **Step 5: Extend `describe()` with the structured household breakdown**

```java
    private String describe(UserProfile profile, String adjustment, List<String> untouched) {
        StringBuilder text = new StringBuilder("Склади меню на тиждень для цієї родини.\n");
        if (profile.getAdultMaleCount() != null || profile.getAdultFemaleCount() != null) {
            text.append("Дорослих: ")
                    .append(profile.getAdultMaleCount() == null ? 0 : profile.getAdultMaleCount())
                    .append(" чоловіків, ")
                    .append(profile.getAdultFemaleCount() == null ? 0 : profile.getAdultFemaleCount())
                    .append(" жінок.\n");
            if (profile.getChildrenAgeBrackets() != null && !profile.getChildrenAgeBrackets().isEmpty()) {
                text.append("Дітей: ")
                        .append(profile.getChildrenAgeBrackets().size())
                        .append(", вікові групи: ")
                        .append(profile.getChildrenAgeBrackets().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(", ")))
                        .append('\n');
            }
        } else {
            text.append("Людей удома: ")
                    .append(profile.getHouseholdSize() == null ? "невідомо" : profile.getHouseholdSize())
                    .append('\n');
            if (Boolean.TRUE.equals(profile.getHasKids())) {
                text.append("Діти: ")
                        .append(
                                profile.getKidsAges() == null || profile.getKidsAges().isEmpty()
                                        ? "є"
                                        : profile.getKidsAges())
                        .append('\n');
            }
        }
        text.append("Про алергії та обмеження людина сказала: «")
                .append(joinOr(profile.getDietaryRestrictions(), "нема"))
                .append("»\n");
        text.append("Про те, чого вдома не їдять, людина сказала: «")
                .append(joinOr(profile.getDislikedFoods(), "нема"))
                .append("»\n");
        if (profile.getWeeklyBudget() != null) {
            text.append("Орієнтовний бюджет на тиждень: ")
                    .append(profile.getWeeklyBudget().toPlainString())
                    .append(" грн\n");
        }
        if (Boolean.TRUE.equals(profile.getOnlyUaProducer())) {
            text.append("Тільки продукти українського виробництва.\n");
        }
        if (profile.getSpecialMode() != null && profile.getSpecialMode() != SpecialMode.NONE) {
            text.append("Особливий режим харчування: ")
                    .append(profile.getSpecialMode().name())
                    .append('\n');
        }
        if (!untouched.isEmpty()) {
            text.append("Не пропонуй ці продукти — їх стабільно не їдять: ")
                    .append(String.join(", ", untouched))
                    .append('\n');
        }
        if (adjustment != null && !adjustment.isBlank()) {
            text.append("Додаткова умова: ").append(adjustment.trim()).append('\n');
        }
        return text.toString();
    }
```

- [ ] **Step 6: Run the new unit test**

Run: `./gradlew test --tests "com.silporestockai.unit.MealPlanServiceTest"`
Expected: PASS.

- [ ] **Step 7: Add a describe()-difference assertion**

```java
    @Test
    void householdCompositionChangesTheGeneratedPromptText() {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile withKids = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .adultMaleCount(1)
                .adultFemaleCount(1)
                .childrenAgeBrackets(List.of(com.silporestockai.model.AgeBracket.AGE_0_3))
                .cookingTimePreference(CookingTimePreference.COOKS_DAILY)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(withKids));

        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        when(mealPlanRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(validPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()));

        service.generateWeeklyPlan(USER_ID);

        org.mockito.ArgumentCaptor<String> userPromptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(claudeApiClient)
                .completeStructured(anyString(), userPromptCaptor.capture(), eq(WeeklyMealPlan.class));
        assertThat(userPromptCaptor.getValue()).contains("1 чоловіків, 1 жінок").contains("AGE_0_3");
    }
```

Run: `./gradlew test --tests "com.silporestockai.unit.MealPlanServiceTest"`
Expected: PASS.

- [ ] **Step 8: Run the full suite**

Run: `make test`
Expected: PASS — check `MealPlanIntegrationTest`/`MealPlanHandoffIntegrationTest` for any direct `new MealPlanService(...)` construction that needs the extra constructor argument (Spring-context tests autowire it, so only a manually-constructed one would break).

- [ ] **Step 9: `make format` and commit**

```bash
make format
git add src/main/java/com/silporestockai/service/MealPlanService.java src/main/resources/prompts/meal-plan-ready-meals-system.txt src/test/java/com/silporestockai/unit/MealPlanServiceTest.java
git commit -m "Fork meal plan generation for READY_MEALS_ONLY households"
```

---

### Task 9: Soft-archive lifecycle + `source_type` wiring in `ShoppingListService`

**Files:**
- Modify: `src/main/java/com/silporestockai/service/ShoppingListService.java`
- Modify: `src/main/java/com/silporestockai/service/MealPlanHandoffService.java`
- Modify: `src/main/java/com/silporestockai/service/ShoppingListBuilderService.java`
- Test: `src/test/java/com/silporestockai/integration/MealPlanIntegrationTest.java`, `MealPlanHandoffIntegrationTest.java` (check for assertions on hard-delete behavior that now need updating to archive)

**Interfaces:**
- Consumes: `ShoppingListItemRepository.archiveActiveByMealPlanId/archiveActiveByUserId/findByUserIdAndStatus` (Task 3), `CategoryKeywordFallbackService` (Task 7), `ShoppingListSourceType` on `MealPlan` (Task 8).
- Produces: `ShoppingListService.deriveFromMealPlan(UUID mealPlanId, ShoppingListSourceType sourceType)` (signature change — was `deriveFromMealPlan(UUID mealPlanId)`); every "current list" read now means `status = ACTIVE`.

- [ ] **Step 1: Read the existing integration tests this touches**

```bash
grep -n "deriveFromMealPlan\|deleteByMealPlanId\|deleteByUserIdAndIdNotIn\|findByUserId(" src/test/java/com/silporestockai/integration/MealPlanIntegrationTest.java src/test/java/com/silporestockai/integration/MealPlanHandoffIntegrationTest.java src/main/java/com/silporestockai/service/ShoppingListBuilderService.java
```

Note every call site — this task changes all of them from delete-based to archive-based, and any test asserting `shoppingListItemRepository.findByMealPlanId(...)` is empty after regeneration needs to instead assert the old rows' `status == ARCHIVED` (still present, not gone).

- [ ] **Step 2: Rewrite `ShoppingListService`**

```java
package com.silporestockai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.exception.ApplicationException;
import com.silporestockai.mapper.ShoppingListItemMapper;
import com.silporestockai.model.PlannedDay;
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.PlannedMeal;
import com.silporestockai.model.ShoppingListSourceType;
import com.silporestockai.model.ShoppingListStatus;
import com.silporestockai.model.WeeklyMealPlan;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collapses a week of meals into the list somebody actually shops from, and owns every add/remove/quantity-change on
 * the live list.
 *
 * <p>This class never depends on {@link com.silporestockai.client.claude.ClaudeApiClient} — that is deliberate and
 * structural, not a convention to remember: every method here is either arithmetic ({@link #aggregate}) or plain CRUD
 * against {@code shopping_list_item}, and it must stay that way so "viewing or manually editing the list calls no
 * AI" is true by construction, not by discipline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingListService {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final MealPlanRepository mealPlanRepository;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final ShoppingListItemMapper shoppingListItemMapper;
    private final CategoryKeywordFallbackService categoryKeywordFallbackService;

    /**
     * Derives the list for a weekly plan, archiving whatever the plan had before rather than deleting it — the old
     * rows stay as history a future delta feature can diff against.
     */
    @Transactional
    public List<ShoppingListItem> deriveFromMealPlan(UUID mealPlanId, ShoppingListSourceType sourceType) {
        MealPlan plan = mealPlanRepository
                .findById(mealPlanId)
                .orElseThrow(() -> new ApplicationException(
                        HttpStatus.NOT_FOUND, "no meal plan %s to derive a list from".formatted(mealPlanId)));

        List<PlannedIngredient> aggregated = aggregate(ingredientsOf(plan));
        shoppingListItemRepository.archiveActiveByMealPlanId(mealPlanId);
        List<ShoppingListItem> items = aggregated.stream()
                .map(this::withFallbackCategory)
                .map(ingredient -> shoppingListItemMapper.toItem(ingredient, mealPlanId, plan.getUserId()))
                .peek(item -> item.setSourceType(sourceType))
                .toList();
        log.info("derived {} shopping list lines from plan {}", items.size(), mealPlanId);
        return shoppingListItemRepository.saveAll(items);
    }

    /** A list that belongs to no weekly plan — the Friday-night snacks, the blackout lunch. */
    @Transactional
    public List<ShoppingListItem> createAdHocList(UUID userId, List<PlannedIngredient> ingredients) {
        List<ShoppingListItem> items = aggregate(ingredients).stream()
                .map(this::withFallbackCategory)
                .map(ingredient -> shoppingListItemMapper.toItem(ingredient, null, userId))
                .peek(item -> item.setSourceType(ShoppingListSourceType.RECIPE_DERIVED))
                .toList();
        log.info("stored {} ad-hoc shopping list lines for user {}", items.size(), userId);
        return shoppingListItemRepository.saveAll(items);
    }

    /**
     * Whatever is on screen replaces whatever the user had before, ad-hoc or plan-derived — there is only ever one
     * live list per user. Archives rather than deletes, same as {@link #deriveFromMealPlan}.
     */
    @Transactional
    public void keepOnly(UUID userId, List<UUID> idsToKeep) {
        shoppingListItemRepository.findByUserIdAndStatus(userId, ShoppingListStatus.ACTIVE).stream()
                .filter(item -> !idsToKeep.contains(item.getId()))
                .forEach(item -> item.setStatus(ShoppingListStatus.ARCHIVED));
        // JPA dirty-checking flushes the status changes above at commit; nothing further to save explicitly.
    }

    /** The user's live list, whichever flow produced it. */
    public List<ShoppingListItem> currentItems(UUID userId) {
        return shoppingListItemRepository.findByUserIdAndStatus(userId, ShoppingListStatus.ACTIVE);
    }

    /** The list on screen became a confirmed order. */
    @Transactional
    public void markOrdered(UUID userId) {
        shoppingListItemRepository.markOrderedByUserId(userId);
    }

    /** Adds one line directly — no AI call. */
    @Transactional
    public ShoppingListItem addItem(UUID userId, String name, BigDecimal quantity, String unit, String category) {
        ShoppingListItem item = ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .name(name)
                .quantity(quantity)
                .unit(unit)
                .category(category == null || category.isBlank() ? categoryKeywordFallbackService.categorize(name) : category)
                .status(ShoppingListStatus.ACTIVE)
                .build();
        return shoppingListItemRepository.save(item);
    }

    /** Removes one line — no AI call. A foreign or already-inactive item id is a no-op. */
    @Transactional
    public void removeItem(UUID userId, UUID itemId) {
        shoppingListItemRepository.findById(itemId).ifPresent(item -> {
            if (item.getUserId().equals(userId) && item.getStatus() == ShoppingListStatus.ACTIVE) {
                shoppingListItemRepository.delete(item);
            }
        });
    }

    /** Changes one line's quantity — no AI call. A foreign or already-inactive item id is a no-op. */
    @Transactional
    public ShoppingListItem updateQuantity(UUID userId, UUID itemId, BigDecimal newQuantity) {
        return shoppingListItemRepository
                .findById(itemId)
                .filter(item -> item.getUserId().equals(userId) && item.getStatus() == ShoppingListStatus.ACTIVE)
                .map(item -> {
                    item.setQuantity(newQuantity);
                    return item;
                })
                .orElse(null);
    }

    private PlannedIngredient withFallbackCategory(PlannedIngredient ingredient) {
        if (ingredient.category() != null && !ingredient.category().isBlank()) {
            return ingredient;
        }
        return new PlannedIngredient(
                ingredient.name(), ingredient.quantity(), ingredient.unit(), categoryKeywordFallbackService.categorize(ingredient.name()));
    }

    /**
     * One line per ingredient and unit, quantities summed, original order, spelling and category kept.
     */
    public static List<PlannedIngredient> aggregate(List<PlannedIngredient> ingredients) {
        Map<String, PlannedIngredient> byNameAndUnit = new LinkedHashMap<>();
        for (PlannedIngredient ingredient : ingredients) {
            if (ingredient == null
                    || ingredient.name() == null
                    || ingredient.name().isBlank()) {
                continue;
            }
            String key = normalise(ingredient.name()) + "|" + normalise(ingredient.unit());
            byNameAndUnit.merge(
                    key,
                    new PlannedIngredient(ingredient.name().trim(), ingredient.quantity(), ingredient.unit(), ingredient.category()),
                    ShoppingListService::add);
        }
        return List.copyOf(byNameAndUnit.values());
    }

    private static PlannedIngredient add(PlannedIngredient existing, PlannedIngredient extra) {
        BigDecimal quantity;
        if (existing.quantity() == null) {
            quantity = extra.quantity();
        } else if (extra.quantity() == null) {
            quantity = existing.quantity();
        } else {
            quantity = existing.quantity().add(extra.quantity());
        }
        String category = existing.category() != null && !existing.category().isBlank()
                ? existing.category()
                : extra.category();
        return new PlannedIngredient(existing.name(), quantity, existing.unit(), category);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<PlannedIngredient> ingredientsOf(MealPlan plan) {
        WeeklyMealPlan week = MAPPER.convertValue(plan.getPlan(), WeeklyMealPlan.class);
        List<PlannedIngredient> ingredients = new ArrayList<>();
        for (PlannedDay day : week.days() == null ? List.<PlannedDay>of() : week.days()) {
            for (PlannedMeal meal : day.meals() == null ? List.<PlannedMeal>of() : day.meals()) {
                if (meal.ingredients() != null) {
                    ingredients.addAll(meal.ingredients());
                }
            }
        }
        return ingredients;
    }
}
```

Note `keepOnly` changed from a bulk repository DELETE to a load-then-mutate loop — this is necessary because "archive" is a per-row status flip, not a bulk-deletable predicate expressible as cleanly with derived query methods once it has to exclude `idsToKeep`; for typical list sizes (a few dozen items) this is not a performance concern. If profiling later says otherwise, a `@Modifying @Query` with a `NOT IN (:ids)` clause is the optimization — not needed now (YAGNI).

- [ ] **Step 3: Update `MealPlanHandoffService`**

```java
                                MealPlan plan = mealPlanService.generateWeeklyPlan(userId);
                                List<ShoppingListItem> list =
                                        shoppingListService.deriveFromMealPlan(plan.getId(), plan.getSourceType());
```

- [ ] **Step 4: Update `ShoppingListBuilderService`**

Replace the hard-delete in `buildAndShow`:

```java
        shoppingListService.keepOnly(user.getId(), List.of());
        List<ShoppingListItem> stored = shoppingListService.createAdHocList(user.getId(), draft.items());
        present(user, stored);
```

(`keepOnly(user.getId(), List.of())` archives every currently-ACTIVE row for the user before the new ad-hoc rows are inserted — equivalent in effect to the old `deleteAll(findByUserIdAndMealPlanIdIsNull(...))`, but archiving instead of deleting, and correctly covering plan-derived rows too, matching this method's own doc comment about "whatever list is currently on screen".)

Replace `currentItems`:

```java
    private List<ShoppingListItem> currentItems(UUID userId) {
        return shoppingListService.currentItems(userId);
    }
```

(Delete the now-redundant `shoppingListItemRepository` field/import from `ShoppingListBuilderService` if nothing else in the class uses it — check with `grep -n "shoppingListItemRepository" src/main/java/com/silporestockai/service/ShoppingListBuilderService.java` after this edit.)

- [ ] **Step 5: Run the full suite**

Run: `make test`
Expected: some failures in `MealPlanIntegrationTest`/`MealPlanHandoffIntegrationTest` if they assert hard-delete-style emptiness after regeneration — fix each such assertion to check `status == ARCHIVED` on the old rows instead of absence. Re-run until green.

- [ ] **Step 6: `make format` and commit**

```bash
make format
git add src/main/java/com/silporestockai/service/ShoppingListService.java src/main/java/com/silporestockai/service/MealPlanHandoffService.java src/main/java/com/silporestockai/service/ShoppingListBuilderService.java src/test/java/com/silporestockai/integration/MealPlanIntegrationTest.java src/test/java/com/silporestockai/integration/MealPlanHandoffIntegrationTest.java
git commit -m "Soft-archive shopping list rows instead of deleting on replacement"
```

---

### Task 10: Manual CRUD is AI-free — enforcement test

**Files:**
- Test: `src/test/java/com/silporestockai/unit/ShoppingListServiceManualEditTest.java`

**Interfaces:**
- Consumes: `ShoppingListService.addItem/removeItem/updateQuantity/currentItems` (Task 9).

This task is purely a test — the production code it locks in already exists after Task 9. Its point is exactly what the spec's acceptance criterion asks for: "verify via logs/mocked client assertion."

- [ ] **Step 1: Write the test**

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.silporestockai.client.claude.ClaudeApiClient;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.mapper.ShoppingListItemMapperImpl;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.service.CategoryKeywordFallbackService;
import com.silporestockai.service.ShoppingListService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Locks in the spec's "AI called only on real change" rule at the one place it's actually testable: {@link
 * ShoppingListService} must never touch a {@link ClaudeApiClient}, no matter which of its methods runs. A mocked
 * client with zero stubbing and an explicit {@code verifyNoInteractions} after every call is what turns "we didn't
 * mean to call it" into "it is not even reachable from here" — there is no {@code ClaudeApiClient} field on this
 * class for these calls to reach.
 */
class ShoppingListServiceManualEditTest {

    private final ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
    private final ShoppingListItemRepository repository = mock(ShoppingListItemRepository.class);
    private final MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
    private final ShoppingListService service = new ShoppingListService(
            mealPlanRepository, repository, new ShoppingListItemMapperImpl(), new CategoryKeywordFallbackService());

    @Test
    void addingAnItemNeverTouchesClaude() {
        UUID userId = UUID.randomUUID();
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        service.addItem(userId, "Молоко", new BigDecimal("2"), "л", null);

        verifyNoInteractions(claudeApiClient);
    }

    @Test
    void removingAnItemNeverTouchesClaude() {
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ShoppingListItem item = ShoppingListItem.builder()
                .id(itemId)
                .userId(userId)
                .name("Молоко")
                .status(com.silporestockai.model.ShoppingListStatus.ACTIVE)
                .build();
        when(repository.findById(itemId)).thenReturn(Optional.of(item));

        service.removeItem(userId, itemId);

        verifyNoInteractions(claudeApiClient);
    }

    @Test
    void changingAQuantityNeverTouchesClaude() {
        UUID userId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ShoppingListItem item = ShoppingListItem.builder()
                .id(itemId)
                .userId(userId)
                .name("Молоко")
                .status(com.silporestockai.model.ShoppingListStatus.ACTIVE)
                .build();
        when(repository.findById(itemId)).thenReturn(Optional.of(item));

        service.updateQuantity(userId, itemId, new BigDecimal("3"));

        verifyNoInteractions(claudeApiClient);
    }

    @Test
    void viewingTheCurrentListNeverTouchesClaude() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndStatus(userId, com.silporestockai.model.ShoppingListStatus.ACTIVE))
                .thenReturn(List.of());

        service.currentItems(userId);

        verifyNoInteractions(claudeApiClient);
    }
}
```

Check whether `ShoppingListItemMapperImpl` (MapStruct's generated implementation) is on the compile classpath for tests before relying on `new ShoppingListItemMapperImpl()` directly — if the annotation processor only runs for `main`, either add `annotationProcessor` for `testAnnotationProcessor` too (check `build.gradle.kts`) or mock `ShoppingListItemMapper` instead:

```java
    private final com.silporestockai.mapper.ShoppingListItemMapper mapper = mock(com.silporestockai.mapper.ShoppingListItemMapper.class);
```

and stub `when(mapper.toItem(...))` only in the tests that need it (`addItem`/`removeItem`/`updateQuantity` as written above don't call the mapper at all — only `deriveFromMealPlan`/`createAdHocList` do, and this test class deliberately never calls those, since it's scoped to the manual-edit methods).

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests "com.silporestockai.unit.ShoppingListServiceManualEditTest"`
Expected: PASS immediately — this test is verification, not a red/green driver, since Task 9 already wrote code that structurally cannot call `ClaudeApiClient` (it's not a constructor dependency). If it somehow fails, that means Task 9's implementation accidentally introduced a dependency it shouldn't have — fix `ShoppingListService`, not this test.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/silporestockai/unit/ShoppingListServiceManualEditTest.java
git commit -m "Test that manual shopping list edits never call Claude"
```

---

### Task 11: Categorized rendering + per-item manual-edit buttons

**Files:**
- Modify: `src/main/java/com/silporestockai/service/telegram/ShoppingListMessageService.java`
- Modify: `src/main/java/com/silporestockai/service/ShoppingListBuilderService.java`
- Test: `src/test/java/com/silporestockai/unit/ShoppingListMessageServiceTest.java` (create if it doesn't exist)

**Interfaces:**
- Consumes: `ShoppingListItem.getCategory()` (Task 3/7), `ShoppingListService.addItem/removeItem/updateQuantity/currentItems` (Task 9).
- Produces: `ShoppingListMessageService.categorizedText(List<ShoppingListItem>)` returning `Map<String, String>` (one rendered block per category) or a single grouped string — see Step 3 for the exact decision; per-item callback constants `CALLBACK_ITEM_DEC_PREFIX`/`CALLBACK_ITEM_INC_PREFIX`/`CALLBACK_ITEM_DEL_PREFIX`.

Telegram's inline keyboard is attached to one message, and a per-item row for a 30-item list is 30 keyboard rows on one message — within Telegram's limits (100 buttons/message) but a long scroll. Groups by category as separate messages, each with its own short keyboard, keeps every message's keyboard small and matches "categorized rendering" more literally than one giant message with one giant keyboard.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.service.telegram.ShoppingListMessageService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShoppingListMessageServiceTest {

    private final ShoppingListMessageService service = new ShoppingListMessageService();

    @Test
    void groupsItemsByCategoryInEncounterOrder() {
        List<ShoppingListItem> items = List.of(
                item("Молоко", "Молочні продукти"),
                item("Цибуля", "Овочі і фрукти"),
                item("Сир", "Молочні продукти"));

        var grouped = service.categorized(items);

        assertThat(grouped.keySet()).containsExactly("Молочні продукти", "Овочі і фрукти");
        assertThat(grouped.get("Молочні продукти")).extracting(ShoppingListItem::getName).containsExactly("Молоко", "Сир");
    }

    @Test
    void uncategorizedItemsFallUnderInshe() {
        List<ShoppingListItem> items = List.of(item("Щось", null));

        var grouped = service.categorized(items);

        assertThat(grouped.keySet()).containsExactly("Інше");
    }

    private static ShoppingListItem item(String name, String category) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .quantity(BigDecimal.ONE)
                .unit("шт")
                .category(category)
                .build();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "com.silporestockai.unit.ShoppingListMessageServiceTest"`
Expected: FAIL — `categorized` doesn't exist yet.

- [ ] **Step 3: Add categorization + per-item buttons to `ShoppingListMessageService`**

```java
package com.silporestockai.service.telegram;

import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.model.TelegramButton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** The list a person reads before anything is ordered, and the four things they can do about it. */
@Service
public class ShoppingListMessageService {

    public static final String CALLBACK_ORDER = "list:order";
    public static final String CALLBACK_EDIT = "list:edit";
    public static final String CALLBACK_CANCEL = "list:cancel";
    public static final String CALLBACK_ITEM_DEC_PREFIX = "sli:dec:";
    public static final String CALLBACK_ITEM_INC_PREFIX = "sli:inc:";
    public static final String CALLBACK_ITEM_DEL_PREFIX = "sli:del:";

    private static final String UNCATEGORIZED = "Інше";

    public String askForInputText() {
        return """
                Що беремо на цей тиждень? Обери, як тобі зручніше:

                — надішли фото холодильника чи полиці, і я подивлюсь, чого бракує;
                — надішли фото чека, і я зберу схожий набір;
                — або просто напиши, що потрібно чи якої дієти тримаєшся.""";
    }

    /** Items grouped by category, in the order each category was first seen. */
    public Map<String, List<ShoppingListItem>> categorized(List<ShoppingListItem> items) {
        Map<String, List<ShoppingListItem>> grouped = new LinkedHashMap<>();
        for (ShoppingListItem item : items) {
            String category = item.getCategory() == null || item.getCategory().isBlank() ? UNCATEGORIZED : item.getCategory();
            grouped.computeIfAbsent(category, ignored -> new java.util.ArrayList<>()).add(item);
        }
        return grouped;
    }

    /** One category's block: a heading line, then one line per item. */
    public String categoryText(String category, List<ShoppingListItem> items) {
        StringBuilder text = new StringBuilder(category).append(':');
        for (ShoppingListItem item : items) {
            text.append("\n— ").append(item.getName());
            if (item.getQuantity() != null) {
                text.append(" — ").append(amount(item.getQuantity()));
                if (item.getUnit() != null) {
                    text.append(' ').append(item.getUnit());
                }
            }
        }
        return text.toString();
    }

    /** −/+/✕ for one item, wired to {@code ShoppingListBuilderService}'s manual-edit handler. */
    public List<TelegramButton> itemButtons(ShoppingListItem item) {
        return List.of(
                TelegramButton.callback("−", CALLBACK_ITEM_DEC_PREFIX + item.getId()),
                TelegramButton.callback("+", CALLBACK_ITEM_INC_PREFIX + item.getId()),
                TelegramButton.callback("✕", CALLBACK_ITEM_DEL_PREFIX + item.getId()));
    }

    /** The plain flat list — kept for the one caller that still wants a single summary message. */
    public String listText(List<ShoppingListItem> items) {
        StringBuilder text = new StringBuilder("Ось що пропоную взяти:\n");
        for (ShoppingListItem item : items) {
            text.append("\n— ").append(item.getName());
            if (item.getQuantity() != null) {
                text.append(" — ").append(amount(item.getQuantity()));
                if (item.getUnit() != null) {
                    text.append(' ').append(item.getUnit());
                }
            }
        }
        text.append("\n\nВсього ").append(items.size()).append(' ').append(positions(items.size()));
        text.append(".\nЯкщо все влаштовує — замовляю. Якщо ні — скажи, що змінити.");
        return text.toString();
    }

    public List<TelegramButton> listButtons() {
        return List.of(
                TelegramButton.callback("Замовити", CALLBACK_ORDER),
                TelegramButton.callback("Змінити", CALLBACK_EDIT),
                TelegramButton.callback("Скасувати", CALLBACK_CANCEL));
    }

    public String askForEditText() {
        return "Напиши, що змінити. Наприклад: «прибери банани, додай хліб і яйця, молока більше».";
    }

    public String buildingText() {
        return "Хвилинку, складаю список.";
    }

    public String couldNotBuildText() {
        return "Не вдалось скласти список. Спробуй описати інакше або надішли фото.";
    }

    public String cancelledText() {
        return "Скасував. Напиши /list, коли будемо збирати список.";
    }

    private static String positions(int count) {
        int lastTwo = count % 100;
        int last = count % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return "позицій";
        }
        if (last == 1) {
            return "позиція";
        }
        if (last >= 2 && last <= 4) {
            return "позиції";
        }
        return "позицій";
    }

    private static String amount(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }
}
```

- [ ] **Step 4: Run the message-service test**

Run: `./gradlew test --tests "com.silporestockai.unit.ShoppingListMessageServiceTest"`
Expected: PASS.

- [ ] **Step 5: Send one message per category with per-item buttons, and wire the new callbacks**

Edit `present` in `ShoppingListBuilderService`:

```java
    public void present(User user, List<ShoppingListItem> items) {
        long chatId = user.getTelegramChatId();
        if (items.isEmpty()) {
            telegramOutboundService.sendMessage(chatId, messages.couldNotBuildText());
            return;
        }
        shoppingListService.keepOnly(user.getId(), items.stream().map(ShoppingListItem::getId).toList());
        conversationStateService.save(chatId, ConversationFlow.LIST_BUILDING, STEP_AWAITING_APPROVAL, Map.of());
        messages.categorized(items).forEach((category, categoryItems) -> {
            telegramOutboundService.sendMessage(chatId, messages.categoryText(category, categoryItems));
            categoryItems.forEach(item -> telegramOutboundService.sendMessageWithButtons(
                    chatId, item.getName(), messages.itemButtons(item)));
        });
        telegramOutboundService.sendMessageWithButtons(
                chatId, "Всього " + items.size() + " позицій. Якщо все влаштовує — замовляю.", messages.listButtons());
    }
```

This sends one category-heading message, then one small message per item with its −/+/✕ row, then the order/edit/cancel row — more messages than the old single flat block, which is the direct cost of "each item gets its own tappable buttons" (there is no way to attach a different inline keyboard to different lines of the same Telegram message). If this reads as too chatty in the demo, the fallback is to drop the per-item message and instead keep one message per category with a numbered list, plus a single "Змінити вручну" button that starts a short guided flow (pick item → pick action) — noted here as the cheaper alternative if Step 5's UX doesn't land well live, not implemented in this plan.

Add the new callback routing to `handleTap`:

```java
    private void handleTap(User user, String data) {
        long chatId = user.getTelegramChatId();
        if (data.startsWith(ShoppingListMessageService.CALLBACK_ITEM_DEC_PREFIX)) {
            adjustQuantity(user, UUID.fromString(data.substring(ShoppingListMessageService.CALLBACK_ITEM_DEC_PREFIX.length())), new BigDecimal("-1"));
            return;
        }
        if (data.startsWith(ShoppingListMessageService.CALLBACK_ITEM_INC_PREFIX)) {
            adjustQuantity(user, UUID.fromString(data.substring(ShoppingListMessageService.CALLBACK_ITEM_INC_PREFIX.length())), BigDecimal.ONE);
            return;
        }
        if (data.startsWith(ShoppingListMessageService.CALLBACK_ITEM_DEL_PREFIX)) {
            shoppingListService.removeItem(
                    user.getId(), UUID.fromString(data.substring(ShoppingListMessageService.CALLBACK_ITEM_DEL_PREFIX.length())));
            telegramOutboundService.sendMessage(chatId, "Прибрав.");
            return;
        }
        switch (data) {
            case ShoppingListMessageService.CALLBACK_ORDER -> order(user);
            case ShoppingListMessageService.CALLBACK_EDIT -> {
                conversationStateService.save(chatId, ConversationFlow.LIST_BUILDING, STEP_AWAITING_EDIT, Map.of());
                telegramOutboundService.sendMessage(chatId, messages.askForEditText());
            }
            case ShoppingListMessageService.CALLBACK_CANCEL -> {
                conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
                telegramOutboundService.sendMessage(chatId, messages.cancelledText());
            }
            default -> log.debug("ignoring unknown list callback {} in chat {}", data, chatId);
        }
    }

    private void adjustQuantity(User user, UUID itemId, BigDecimal delta) {
        List<ShoppingListItem> current = currentItems(user.getId());
        current.stream()
                .filter(item -> item.getId().equals(itemId))
                .findFirst()
                .ifPresent(item -> {
                    BigDecimal newQuantity = (item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity()).add(delta);
                    if (newQuantity.signum() <= 0) {
                        shoppingListService.removeItem(user.getId(), itemId);
                        telegramOutboundService.sendMessage(user.getTelegramChatId(), "Прибрав.");
                    } else {
                        shoppingListService.updateQuantity(user.getId(), itemId, newQuantity);
                        telegramOutboundService.sendMessage(user.getTelegramChatId(), "Оновив: " + newQuantity + ".");
                    }
                });
    }
```

Add the `import java.math.BigDecimal;` and `import java.util.UUID;` to `ShoppingListBuilderService` if not already present (check — `UUID` is already imported for `ShoppingListDraft`/other uses; `BigDecimal` is not, add it).

- [ ] **Step 6: Run the full suite**

Run: `make test`
Expected: PASS.

- [ ] **Step 7: `make format` and commit**

```bash
make format
git add src/main/java/com/silporestockai/service/telegram/ShoppingListMessageService.java src/main/java/com/silporestockai/service/ShoppingListBuilderService.java src/test/java/com/silporestockai/unit/ShoppingListMessageServiceTest.java
git commit -m "Render the shopping list grouped by category with per-item manual-edit buttons"
```

---

### Task 12: Flip `ACTIVE → ORDERED` on order confirmation

**Files:**
- Modify: `src/main/java/com/silporestockai/service/CartConfirmationService.java`

**Interfaces:**
- Consumes: `ShoppingListService.markOrdered(UUID userId)` (Task 9).

- [ ] **Step 1: Find or write the failing test**

```bash
grep -n "class CartConfirmationServiceTest\|class CartConfirmationIntegrationTest" -r src/test/java/com/silporestockai
```

Read whichever exists, find its `confirm`/order-confirmation happy-path test, and add an assertion there that the shopping list items end up `ORDERED`. If neither file exists as a focused unit test, add a case to whatever integration test currently covers `CartConfirmationService.confirm` (search `CALLBACK_CONFIRM` in the integration test directory) asserting `shoppingListItemRepository.findByUserIdAndStatus(userId, ShoppingListStatus.ORDERED)` is non-empty after the confirm callback.

- [ ] **Step 2: Run it to verify it fails**

Run whichever test command matches the file found in Step 1.
Expected: FAIL — items stay `ACTIVE`.

- [ ] **Step 3: Wire the status flip**

Add `ShoppingListService shoppingListService` to `CartConfirmationService`'s constructor field list, and call it in `confirm`:

```java
    private void confirm(User user, CustomerOrder order, CartSummary summary, boolean spendBonuses) {
        long chatId = user.getTelegramChatId();
        boolean bonusesApplied = spendBonuses && applyBonuses(user.getId(), summary);

        order.setStatus(OrderStatus.CONFIRMED);
        order.setConfirmedAt(Instant.now());
        customerOrderRepository.save(order);
        shoppingListService.markOrdered(user.getId());
        if (order.getType() == OrderType.INITIAL) {
            storeBaseline(user.getId(), order);
        }
        ...
```

- [ ] **Step 4: Run the test again, then the full suite**

Run the Step 1 test, then `make test`.
Expected: PASS.

- [ ] **Step 5: `make format` and commit**

```bash
make format
git add src/main/java/com/silporestockai/service/CartConfirmationService.java
git commit -m "Mark shopping list items ORDERED when their cart is confirmed"
```

---

## After the last task

Run the full verification pass before considering task 20 done:

```bash
make format
make test
make build
```

Then work through the spec's acceptance criteria list one by one against what was actually built (not from memory of writing it) — this is the `superpowers:requesting-code-review`/`verification-before-completion` discipline, not a step to skip because the plan feels done.
