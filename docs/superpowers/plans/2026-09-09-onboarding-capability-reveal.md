# Onboarding Capability Reveal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Right after a household's first weekly plan lands, push one proactive «ось що я ще вмію» message — once per user, reusing the exact copy already behind the «❓ Інструкція» button.

**Architecture:** The «Інструкція» copy moves out of `IntentRouterService` into a single holder, `service/telegram/HelpContent`, where each example phrase is a named constant. `HelpContent.FULL` composes the button block, every example line and the group paragraph — byte-identical to today's message. `HelpContent.REVEAL` composes a lead-in, five of those *same* constants, and a pointer back to «Інструкція». A new `CapabilityRevealService` sends `REVEAL` and stamps `user_profile.capability_reveal_sent_at`; it is a no-op once that stamp exists. `MealPlanHandoffService.generateFirstPlan` calls it at the end of its success branch only.

**Tech Stack:** Java 21, Spring Boot 4, Liquibase, JPA/Hibernate (`ddl-auto: validate`), JUnit 5 + AssertJ, Testcontainers Postgres, stub Telegram/Anthropic/MCP servers under `src/test/java/com/silporestockai/support`.

**Spec:** Notion task «70. Onboarding capability reveal: proactive «що я вмію» message right after profile setup» (`https://app.notion.com/p/3d67227def1c81ce8befdfa9188d9a8c`). Approved in-chat design, session 17.

## Global Constraints

- **No duplicated copy.** Every sentence the reveal sends must be the *same Java constant* the «Інструкція» button renders. A string literal that appears twice is a plan failure.
- **Liquibase owns the schema.** A new column needs a changeset under `src/main/resources/db/changelog/changes/` named `032-...yaml`; `spring.jpa.hibernate.ddl-auto: validate` means a missing changeset fails *every* `@SpringBootTest`.
- **ArchUnit is enforced.** Constructor injection only (`@RequiredArgsConstructor`, never `@Autowired` on a field); a `@Service`-annotated bean must be named `...Service`; `..service..` may be reached only from `..controller..`, `..job..` and itself.
- **Spotless (palantir).** Run `make format` before the final commit; CI runs `spotlessCheck` before `build`.
- **`@Slf4j` for logging**, never a manual `LoggerFactory`.
- **Ukrainian copy, verbatim.** Where this plan quotes message text, copy it character for character — the em-dashes are `—` (U+2014), the quotes are `«»`, and the apostrophes in «п'ятниці» / «сім'ї» are `'` (U+0027) exactly as the existing file has them.
- **This is a small task.** Do not run the full suite. Compile, then run only the test classes named in each task.

---

### Task 1: Extract the «Інструкція» copy into one holder

No behaviour change: the button must send exactly the bytes it sends today. This task exists so Task 3 has one source to read from.

**Files:**
- Create: `src/main/java/com/silporestockai/service/telegram/HelpContent.java`
- Modify: `src/main/java/com/silporestockai/service/IntentRouterService.java:36-63` (delete `HELP_TEXT`), `:124-126` (`sendHelp` reads `HelpContent.FULL`)
- Test: `src/test/java/com/silporestockai/unit/HelpContentTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `com.silporestockai.service.telegram.HelpContent`, a `final` class with a private constructor and these `public static final String` members: `FULL`, `REVEAL`. The five example constants `EXAMPLE_AD_HOC_ORDER`, `EXAMPLE_LIST_EDIT`, `EXAMPLE_DISH`, `EXAMPLE_SPECIAL_MODE`, `EXAMPLE_BLACKOUT` are `static final` and package-private-visible to the test in the same module (declare them `public static final` so the test in `..unit..` can read them). Task 3 uses `HelpContent.REVEAL` only.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/unit/HelpContentTest.java`:

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.service.telegram.HelpContent;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole point of {@link HelpContent} is that «Інструкція» and the onboarding reveal (task 70) cannot drift
 * apart, so the test that matters is the containment one: every line the reveal shows is a line the button shows.
 */
@DisplayName("the help copy has exactly one source")
class HelpContentTest {

    @Test
    void theButtonStillRendersTheWholeInstruction() {
        assertThat(HelpContent.FULL)
                .startsWith("Кнопки внизу:")
                .contains("❓ Інструкція — це повідомлення.")
                .contains("Усе інше — просто напиши. Наприклад:")
                .contains("— «Голова після вчорашнього» — мінералка й сорбенти, найближча доставка.")
                .contains("— «Підключи Google Календар» — вноситиму доставки в календар.")
                .contains("Компанією: додай мене в груповий чат");
    }

    @Test
    void everyLineTheRevealShowsIsALineTheInstructionShows() {
        List<String> revealed = List.of(
                HelpContent.EXAMPLE_AD_HOC_ORDER,
                HelpContent.EXAMPLE_LIST_EDIT,
                HelpContent.EXAMPLE_DISH,
                HelpContent.EXAMPLE_SPECIAL_MODE,
                HelpContent.EXAMPLE_BLACKOUT);
        for (String line : revealed) {
            assertThat(HelpContent.REVEAL).contains(line);
            assertThat(HelpContent.FULL).contains(line);
        }
    }

    /** The reveal is the teaser, not the reference — it must stay short and point at the full list. */
    @Test
    void theRevealIsShorterThanTheInstructionAndPointsAtIt() {
        assertThat(HelpContent.REVEAL.length()).isLessThan(HelpContent.FULL.length());
        assertThat(HelpContent.REVEAL).contains("❓ Інструкція");
        assertThat(HelpContent.REVEAL).doesNotContain("Кнопки внизу:");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.silporestockai.unit.HelpContentTest'`
Expected: FAIL — compilation error, `package com.silporestockai.service.telegram.HelpContent does not exist`.

- [ ] **Step 3: Write `HelpContent`**

Create `src/main/java/com/silporestockai/service/telegram/HelpContent.java`. The example lines below are moved verbatim out of `IntentRouterService.HELP_TEXT` — do not retype them from this plan if the file differs; the file wins.

```java
package com.silporestockai.service.telegram;

/**
 * The one place the bot's «що я вмію» copy lives.
 *
 * <p>It is rendered twice: in full by the «❓ Інструкція» button (task 31), and as a five-line teaser pushed once
 * right after a household's first plan (task 70). Both read the same constants on purpose — a capability whose
 * wording is maintained in two files is a capability that will eventually be described two different ways.
 */
public final class HelpContent {

    private static final String BUTTONS =
            """
            Кнопки внизу:
            📝 Список — поточний список покупок: замовити або змінити.
            📦 Замовлення — що з останнім замовленням: статус, сума, час доставки.
            🗓 Заплановані — разові замовлення, які ще не виконав: змінити або скасувати.
            🧾 Анкета — склад сім'ї, дієта, бюджет.
            ❓ Інструкція — це повідомлення.
            💬 Фідбек — напиши нам, що не так або що покращити; одне повідомлення, без обробки.""";

    public static final String EXAMPLE_AD_HOC_ORDER =
            "— «Замов до п'ятниці вино та сир зі знижкою» — разове замовлення поза тижневим планом.";
    public static final String EXAMPLE_TOP_UP = "— «Що треба докупити?» — зберу дозамовлення того, що закінчується.";
    public static final String EXAMPLE_LIST_EDIT = "— «Прибери молоко зі списку, додай яйця» — правка поточного списку.";
    public static final String EXAMPLE_LIKE_LAST_TIME =
            "— «Зроби список як минулого разу» — покажу твої останні замовлення в «Сільпо», візьму обране за основу.";
    public static final String EXAMPLE_ORDER_STATUS =
            "— «Де моє замовлення?» — статус і час доставки останнього замовлення (те саме, що кнопка «Замовлення»).";
    public static final String EXAMPLE_DISH =
            "— «Замов усе для карбонари» (або фото готової страви з таким підписом) — зберу інгредієнти на одну страву.";
    public static final String EXAMPLE_SPECIAL_MODE =
            "— «Я захворів, гастрит» — тимчасово щадне харчування, потім сам поверну звичайне.";
    public static final String EXAMPLE_LOWER_CALORIES = "— «Зроби менш калорійним» — той самий раціон, менше калорій.";
    public static final String EXAMPLE_BULK = "— «Хочу набрати масу» — план під набір маси.";
    public static final String EXAMPLE_BACK_TO_NORMAL =
            "— «Повертаємось до звичайного раціону» — вимкнути будь-який спецрежим.";
    public static final String EXAMPLE_UA_ONLY =
            "— «Шукай тільки українського виробника» — фільтр на всі наступні пошуки.";
    public static final String EXAMPLE_HANGOVER =
            "— «Голова після вчорашнього» — мінералка й сорбенти, найближча доставка.";
    public static final String EXAMPLE_BLACKOUT = "— «Світло вимкнули» — їжа без плити й холодильника.";
    public static final String EXAMPLE_WEEKDAY = "— «Що їмо в середу?» — раціон по днях.";
    public static final String EXAMPLE_CALENDAR = "— «Підключи Google Календар» — вноситиму доставки в календар.";

    private static final String GROUP =
            """
            Компанією: додай мене в груповий чат — зберу напої на всіх за спільною згодою. Кожен пише реплаєм, \
            що п'є, організатор закриває список, я пропоную, усі тиснуть 👍 — і кошик у «Сільпо» організатора.""";

    /** Everything, behind the «❓ Інструкція» button. */
    public static final String FULL = BUTTONS
            + "\n\nУсе інше — просто напиши. Наприклад:\n"
            + String.join(
                    "\n",
                    EXAMPLE_AD_HOC_ORDER,
                    EXAMPLE_TOP_UP,
                    EXAMPLE_LIST_EDIT,
                    EXAMPLE_LIKE_LAST_TIME,
                    EXAMPLE_ORDER_STATUS,
                    EXAMPLE_DISH,
                    EXAMPLE_SPECIAL_MODE,
                    EXAMPLE_LOWER_CALORIES,
                    EXAMPLE_BULK,
                    EXAMPLE_BACK_TO_NORMAL,
                    EXAMPLE_UA_ONLY,
                    EXAMPLE_HANGOVER,
                    EXAMPLE_BLACKOUT,
                    EXAMPLE_WEEKDAY,
                    EXAMPLE_CALENDAR)
            + "\n\n"
            + GROUP;

    /**
     * The teaser pushed once after the first plan. Five lines, not fifteen: it is read at a moment nobody asked
     * for it, so it has to be skimmable, and the reference stays one button away.
     */
    public static final String REVEAL = "Поки що ти бачив тільки тижневий план. Я розумію й звичайні прохання — "
            + "просто напиши:\n"
            + String.join(
                    "\n", EXAMPLE_AD_HOC_ORDER, EXAMPLE_LIST_EDIT, EXAMPLE_DISH, EXAMPLE_SPECIAL_MODE, EXAMPLE_BLACKOUT)
            + "\n\nПовний список — кнопка «❓ Інструкція» внизу.";

    private HelpContent() {}
}
```

- [ ] **Step 4: Point `IntentRouterService` at it**

In `src/main/java/com/silporestockai/service/IntentRouterService.java`: delete the whole `private static final String HELP_TEXT = """ ... """;` block (lines 36-63), add the import `com.silporestockai.service.telegram.HelpContent`, and change the body of `sendHelp`:

```java
    /** The static "❓ Інструкція" content — a persistent-menu button, so it never needs a classification call. */
    public void sendHelp(User user) {
        telegramOutboundService.sendMessage(user.getTelegramChatId(), HelpContent.FULL);
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.silporestockai.unit.HelpContentTest' --tests 'com.silporestockai.integration.IntentRouterIntegrationTest'`
Expected: PASS. `IntentRouterIntegrationTest.aVoiceNoteOutsideAnyFlowIsTranscribedAndRoutedLikeText` asserts the sent text contains `"Кнопки внизу"` — that assertion passing is the proof the extraction was lossless.

- [ ] **Step 6: Commit**

```bash
make format
git add src/main/java/com/silporestockai/service/telegram/HelpContent.java \
        src/main/java/com/silporestockai/service/IntentRouterService.java \
        src/test/java/com/silporestockai/unit/HelpContentTest.java
git commit -m "Move the «Інструкція» copy to one holder both readers can share"
```

---

### Task 2: A once-per-user reveal, stamped on the profile

**Files:**
- Create: `src/main/resources/db/changelog/changes/032-user-profile-capability-reveal.yaml`
- Create: `src/main/java/com/silporestockai/service/CapabilityRevealService.java`
- Modify: `src/main/java/com/silporestockai/entity/UserProfile.java` (one field, after `cookingTimePreference`)
- Test: `src/test/java/com/silporestockai/integration/CapabilityRevealIntegrationTest.java` (create)

**Interfaces:**
- Consumes: `HelpContent.REVEAL` from Task 1.
- Produces: `CapabilityRevealService.revealOnce(com.silporestockai.entity.User user)` returning `void`; `UserProfile.getCapabilityRevealSentAt()` / `setCapabilityRevealSentAt(java.time.Instant)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/CapabilityRevealIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CapabilityRevealService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubTelegramServer;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("the capability reveal fires once and then never again")
class CapabilityRevealIntegrationTest extends AbstractIntegrationTest {

    private static final String BOT_TOKEN = "555:stub-bot-token";
    private static final long CHAT_ID = 8301L;
    private static final StubTelegramServer TELEGRAM = startTelegram();

    @Autowired
    private CapabilityRevealService capabilityRevealService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer(BOT_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the Telegram stub", e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add("telegram.bot-token", () -> BOT_TOKEN);
        registry.add("telegram.api-url", TELEGRAM::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TELEGRAM.close();
    }

    @BeforeEach
    void clean() {
        TELEGRAM.reset();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User profiledUser() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .onlyUaProducer(false)
                .build());
        return user;
    }

    @Test
    void sendsTheTeaserAndStampsTheProfile() {
        User user = profiledUser();

        capabilityRevealService.revealOnce(user);

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText())
                .contains("Я розумію й звичайні прохання")
                .contains("«Світло вимкнули»")
                .contains("❓ Інструкція");
        assertThat(userProfileRepository
                        .findByUserId(user.getId())
                        .orElseThrow()
                        .getCapabilityRevealSentAt())
                .isNotNull();
    }

    /** Re-opening «Анкета» later runs the same plan hand-off. It must not re-teach what was already taught. */
    @Test
    void staysSilentOnEverySubsequentCall() {
        User user = profiledUser();

        capabilityRevealService.revealOnce(user);
        capabilityRevealService.revealOnce(user);
        capabilityRevealService.revealOnce(user);

        assertThat(TELEGRAM.sentMessages()).hasSize(1);
    }

    /** No profile means onboarding never finished; there is nothing to stamp and nothing worth saying. */
    @Test
    void saysNothingWhenTheUserHasNoProfileYet() {
        User user = userAccountService.findOrCreate(CHAT_ID);

        capabilityRevealService.revealOnce(user);

        assertThat(TELEGRAM.sentMessages()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.silporestockai.integration.CapabilityRevealIntegrationTest'`
Expected: FAIL — compilation error, `cannot find symbol: class CapabilityRevealService`.

- [ ] **Step 3: Add the column**

Create `src/main/resources/db/changelog/changes/032-user-profile-capability-reveal.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 032-user-profile-capability-reveal
      author: komora
      comment: >-
        When this household was shown the one-time «ось що я ще вмію» teaser that follows their first weekly
        plan. Null means never. A timestamp rather than a boolean for the same reason
        users.last_checkin_prompt_sent_at is one: knowing it happened is worth less than knowing when.
      changes:
        - addColumn:
            tableName: user_profile
            columns:
              - column:
                  name: capability_reveal_sent_at
                  type: TIMESTAMP WITH TIME ZONE
```

- [ ] **Step 4: Add the entity field**

In `src/main/java/com/silporestockai/entity/UserProfile.java`, after the `cookingTimePreference` field:

```java
    /** When the one-time capability teaser (task 70) went out. Null means it has not. */
    @Column(name = "capability_reveal_sent_at")
    private Instant capabilityRevealSentAt;
```

`java.time.Instant` is already imported in that file.

- [ ] **Step 5: Write the service**

Create `src/main/java/com/silporestockai/service/CapabilityRevealService.java`:

```java
package com.silporestockai.service;

import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.telegram.HelpContent;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The one-time «ось що я ще вмію» teaser (task 70).
 *
 * <p>A household that has only ever seen a weekly plan has no way of guessing that «я захворів, гастрит» or
 * «світло вимкнули» do anything — the «❓ Інструкція» button holds that answer, but a button has to be tapped,
 * and nobody taps a button to find out about a feature they do not know exists. So it is pushed once, at the
 * first moment it is relevant, and the stamp on the profile makes sure it is pushed exactly once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityRevealService {

    private final UserProfileRepository userProfileRepository;
    private final TelegramOutboundService telegramOutboundService;

    /** Sends the teaser if this household has never seen it. Otherwise does nothing at all. */
    public void revealOnce(User user) {
        UserProfile profile =
                userProfileRepository.findByUserId(user.getId()).orElse(null);
        if (profile == null || profile.getCapabilityRevealSentAt() != null) {
            return;
        }
        telegramOutboundService.sendMessage(user.getTelegramChatId(), HelpContent.REVEAL);
        profile.setCapabilityRevealSentAt(Instant.now());
        userProfileRepository.save(profile);
        log.info("capability reveal sent to user {}", user.getId());
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.silporestockai.integration.CapabilityRevealIntegrationTest' --tests 'com.silporestockai.architecture.ArchitectureTest'`
Expected: PASS. If the Liquibase changeset is wrong the failure is a Hibernate schema-validation error at context startup, not an assertion failure — read the message before changing the test.

- [ ] **Step 7: Commit**

```bash
make format
git add src/main/resources/db/changelog/changes/032-user-profile-capability-reveal.yaml \
        src/main/java/com/silporestockai/entity/UserProfile.java \
        src/main/java/com/silporestockai/service/CapabilityRevealService.java \
        src/test/java/com/silporestockai/integration/CapabilityRevealIntegrationTest.java
git commit -m "Send the capability teaser once per household, stamped on the profile"
```

---

### Task 3: Fire it after the first plan

**Files:**
- Modify: `src/main/java/com/silporestockai/service/MealPlanHandoffService.java` (one field, one call in the success branch of `generateFirstPlan`)
- Test: `src/test/java/com/silporestockai/integration/MealPlanHandoffIntegrationTest.java` (modify — one existing assertion, one new test)

**Interfaces:**
- Consumes: `CapabilityRevealService.revealOnce(User)` from Task 2.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

In `src/test/java/com/silporestockai/integration/MealPlanHandoffIntegrationTest.java`, add this test after `generatesAPlanAndTellsTheUserWhatIsOnEveryDay`:

```java
    /**
     * Task 70. A household that has only seen a plan does not know free text works at all, so the teaser follows
     * the list unprompted — and only the first time, because re-editing «Анкета» comes back through here.
     */
    @Test
    void teachesWhatElseItUnderstandsOnceTheFirstPlanIsOut() {
        UUID userId = profiledUser();
        CLAUDE.respondWithText(fullWeekJson());

        mealPlanHandoffService.generateFirstPlan(userId);

        String teaser = TELEGRAM.sentMessages().getLast().path("text").asText();
        assertThat(teaser).contains("Я розумію й звичайні прохання").contains("❓ Інструкція");

        TELEGRAM.reset();
        CLAUDE.respondWithText(fullWeekJson());
        mealPlanHandoffService.generateFirstPlan(userId);

        assertThat(TELEGRAM.sentMessages().stream()
                        .map(message -> message.path("text").asText())
                        .filter(text -> text.contains("Я розумію й звичайні прохання"))
                        .count())
                .isZero();
    }

    /** A plan that never arrived teaches nothing — there is no first plan to follow yet. */
    @Test
    void doesNotTeachAnythingWhenTheFirstPlanFailed() {
        UUID userId = profiledUser();
        CLAUDE.respondWithText("{\"days\":[]}");

        mealPlanHandoffService.generateFirstPlan(userId);

        assertThat(TELEGRAM.sentMessages().stream()
                        .map(message -> message.path("text").asText())
                        .filter(text -> text.contains("Я розумію й звичайні прохання"))
                        .count())
                .isZero();
    }
```

Then fix the one existing assertion this shifts. In `generatesAPlanAndTellsTheUserWhatIsOnEveryDay` (currently line ~229) the list message stops being the last one, so replace:

```java
        assertThat(TELEGRAM.sentMessages().getLast().path("text").asText())
                .contains("Ось що пропоную взяти")
                .contains("Всього 5 позицій");
```

with:

```java
        // The list is no longer the last message — task 70's one-time teaser follows it — so look it up by content.
        assertThat(TELEGRAM.sentMessages().stream()
                        .map(sent -> sent.path("text").asText())
                        .filter(text -> text.contains("Ось що пропоную взяти")))
                .singleElement()
                .asString()
                .contains("Всього 5 позицій");
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.silporestockai.integration.MealPlanHandoffIntegrationTest'`
Expected: FAIL — `teachesWhatElseItUnderstandsOnceTheFirstPlanIsOut` fails because the last message is still the shopping list, not the teaser. `doesNotTeachAnythingWhenTheFirstPlanFailed` already passes; that is fine, it is the guard rail.

- [ ] **Step 3: Wire the call**

In `src/main/java/com/silporestockai/service/MealPlanHandoffService.java`, add the field next to the other injected services:

```java
    private final CapabilityRevealService capabilityRevealService;
```

and add the call as the last statement of the `try` block in `generateFirstPlan`, immediately after `shoppingListBuilderService.present(user, list);`:

```java
                                shoppingListBuilderService.present(user, list);
                                // Task 70: the moment free text becomes useful is the moment the first plan
                                // exists, so the teaser goes out here — once, and never on the failure path.
                                capabilityRevealService.revealOnce(user);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.silporestockai.integration.MealPlanHandoffIntegrationTest' --tests 'com.silporestockai.integration.OnboardingFlowIntegrationTest' --tests 'com.silporestockai.integration.ProfileReeditIntegrationTest'`
Expected: PASS, all three classes. The last two are here because they drive the same hand-off from the other direction.

- [ ] **Step 5: Commit**

```bash
make format
git add src/main/java/com/silporestockai/service/MealPlanHandoffService.java \
        src/test/java/com/silporestockai/integration/MealPlanHandoffIntegrationTest.java
git commit -m "Teach a new household what else it can ask for, right after its first plan"
```

---

### Task 4: Manual verification on a fresh onboarding

Not a code change — the acceptance criterion the Notion task calls out by name.

**Files:** none.

- [ ] **Step 1: Start the app against the compose database**

Run: `make run`. Wait for `Started SilpoRestockAiApplication`.

- [ ] **Step 2: Drive a fresh onboarding**

Follow `docs/RUNBOOK.md` — either the Telegram Web path or the synthetic-webhook path (`docs/RUNBOOK.md`, «driving Комора without Telegram»). Use a chat id with no rows in `users`, so `capability_reveal_sent_at` starts null.

- [ ] **Step 3: Check the message order**

Expected, unprompted, with no further input after the profile is submitted: `Записав. Готую перший план на тиждень.` → the weekly plan summary → `Ось що пропоную взяти…` (the list, with its buttons) → the teaser starting `Поки що ти бачив тільки тижневий план.`

- [ ] **Step 4: Check it does not repeat, and «Інструкція» still works**

Tap «🧾 Анкета», change one answer, confirm «Так, оновити». A new list arrives; the teaser must not. Then tap «❓ Інструкція» — the full instruction must render exactly as before, starting `Кнопки внизу:` and ending with the group-chat paragraph.

- [ ] **Step 5: Stop the app**

Ctrl-C the `make run`, then `make db-down` if the compose Postgres is no longer wanted. There is no `make stop` target. Leaving `bootRun` alive makes the next `make test` look flaky — it shares `build/classes`.
