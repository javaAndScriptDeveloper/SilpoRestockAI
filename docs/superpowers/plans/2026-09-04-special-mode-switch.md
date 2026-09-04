# Special-mode switch commands (task 25) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the user real Telegram entry points to switch `user_profile.special_mode` and `only_ua_producer`, with the gastritis case fully modeled end-to-end (free-text trigger → medical plan → two-stage automatic reversion), without ever losing the household's normal `BaselineBasket`.

**Architecture:** A new `SpecialModeService` (mirroring `BlackoutModeService`'s and `CheckinPromptService`'s conventions) owns every transition. It reuses `MealPlanService.regenerateWithAdjustment` (extended to pick a special-mode system prompt), `ShoppingListService.deriveFromMealPlan`, and `ShoppingListBuilderService.present` — the exact same pipeline `MealPlanHandoffService.generateFirstPlan` already uses for a normal weekly plan, so the baseline stays safe by construction (`ShoppingListBuilderService.order()` only ever stores a baseline for `OrderType.INITIAL`, and a household using special modes already has one). A new `SpecialModeScheduler` (`@Scheduled`, thin, mirrors `CheckinScheduler`) drives the ACUTE→DIET_TABLE_5→NONE expiry chain on a configurable, env-overridable duration.

**Tech Stack:** Spring Boot, Spring Data JPA, Liquibase, Anthropic Claude structured output (`ClaudeApiClient.completeStructured`), MockMvc + `StubTelegramServer`/`StubMcpServer`/`StubAnthropicServer` integration tests (Testcontainers Postgres via `AbstractIntegrationTest`).

**Spec:** `docs/superpowers/specs/2026-09-04-special-mode-switch-design.md`

## Global Constraints

- `spring.jpa.hibernate.ddl-auto: validate` — every entity change needs a matching Liquibase changeset under `src/main/resources/db/changelog/changes/`, numbered `021-...` (next after `020-shopping-list-item-silpo-product-id.yaml`); no master-file edit needed, `db.changelog-master.yaml` uses `includeAll`.
- Constructor injection only (`@RequiredArgsConstructor`, no `@Autowired` fields) — ArchUnit-enforced.
- `Service`/`Controller`/`Repository`/`Scheduler` name suffixes; a `Job`-package class is a thin `@Component`, not a `@Service` — matches `CheckinScheduler`.
- `Service` classes are reachable only from `Controller` and `Job` — ArchUnit-enforced.
- Run `make format` (Spotless/Palantir) before every commit; CI runs `spotlessCheck` before `build`.
- No stacking: `SpecialModeService` must reject a trigger while `specialMode != NONE`, per the Notion task's "Out of scope" line.
- Every special-mode order must go through the same pipeline as a normal weekly plan (`ShoppingListBuilderService.present`), never `CartConfirmationService.present(user, items)` directly — that is what keeps `BaselineBasket` safe.
- Config idiom is `${ENV_VAR:default}` inline in `application.yml`; secrets never hardcoded.
- `@Slf4j` for logging, no manual `LoggerFactory`.

---

## Task 1: Schema and entity fields

**Files:**
- Create: `src/main/resources/db/changelog/changes/021-special-mode-expiry.yaml`
- Modify: `src/main/java/com/silporestockai/entity/UserProfile.java`
- Modify: `src/main/java/com/silporestockai/entity/MealPlan.java`
- Test: `src/test/java/com/silporestockai/integration/UserProfileSpecialModeFieldsIntegrationTest.java` (new — confirmed no existing `UserProfileRepository`-focused integration test file exists to extend)

**Interfaces:**
- Produces: `UserProfile.getSpecialModeExpiresAt()/setSpecialModeExpiresAt(Instant)`, `UserProfile.getTargetWeightKg()/setTargetWeightKg(BigDecimal)`, `UserProfile.getTargetCalories()/setTargetCalories(Integer)`, `UserProfile.getTargetProteinG()/setTargetProteinG(Integer)`, `MealPlan.getSpecialMode()/setSpecialMode(SpecialMode)` — later tasks persist and read these.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.UserAccountService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("user_profile carries special-mode expiry and mass-gain targets")
class UserProfileSpecialModeFieldsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void persistsAndReloadsTheNewColumns() {
        User user = userAccountService.findOrCreate(9001L);
        Instant expiresAt = Instant.now().plusSeconds(3600);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .specialMode(SpecialMode.MEDICAL_GASTRITIS_ACUTE)
                .specialModeExpiresAt(expiresAt)
                .targetWeightKg(new BigDecimal("82.5"))
                .targetCalories(3200)
                .targetProteinG(160)
                .build());

        UserProfile reloaded =
                userProfileRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(reloaded.getSpecialModeExpiresAt()).isEqualTo(expiresAt);
        assertThat(reloaded.getTargetWeightKg()).isEqualByComparingTo("82.5");
        assertThat(reloaded.getTargetCalories()).isEqualTo(3200);
        assertThat(reloaded.getTargetProteinG()).isEqualTo(160);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*UserProfileSpecialModeFieldsIntegrationTest*"`
Expected: FAIL — compile error (`specialModeExpiresAt`/`targetWeightKg`/`targetCalories`/`targetProteinG` builder methods do not exist yet) or, once compiled with the entity stub, a validation-mode Liquibase failure on unknown columns.

- [ ] **Step 3: Write the changeset**

```yaml
databaseChangeLog:
  - changeSet:
      id: 021-special-mode-expiry
      author: komora
      comment: >-
        When the current special mode (if any) ends, and the extra parameters a mass-gain plan needs.
        special_mode on meal_plan records which mode (if any) produced that plan row, for future
        UI/debugging use — BaselineBasket, not MealPlan, is what actually protects the household's normal
        basket (see task 25 design doc).
      changes:
        - addColumn:
            tableName: user_profile
            columns:
              - column:
                  name: special_mode_expires_at
                  type: TIMESTAMP WITH TIME ZONE
              - column:
                  name: target_weight_kg
                  type: NUMERIC(5, 2)
              - column:
                  name: target_calories
                  type: INT
              - column:
                  name: target_protein_g
                  type: INT
        - addColumn:
            tableName: meal_plan
            columns:
              - column:
                  name: special_mode
                  type: VARCHAR(64)
```

- [ ] **Step 4: Add the entity fields**

In `UserProfile.java`, after the existing `specialModeStartedAt` field (around line 79):

```java
    @Column(name = "special_mode_expires_at")
    private Instant specialModeExpiresAt;

    @Column(name = "target_weight_kg", precision = 5, scale = 2)
    private BigDecimal targetWeightKg;

    @Column(name = "target_calories")
    private Integer targetCalories;

    @Column(name = "target_protein_g")
    private Integer targetProteinG;
```

In `MealPlan.java`, add imports `com.silporestockai.model.SpecialMode`, `jakarta.persistence.EnumType`, `jakarta.persistence.Enumerated`, and a real (non-`@Transient`) field after `createdAt`:

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "special_mode", length = 64)
    private SpecialMode specialMode;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "*UserProfileSpecialModeFieldsIntegrationTest*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/changelog/changes/021-special-mode-expiry.yaml \
        src/main/java/com/silporestockai/entity/UserProfile.java \
        src/main/java/com/silporestockai/entity/MealPlan.java \
        src/test/java/com/silporestockai/integration/UserProfileSpecialModeFieldsIntegrationTest.java
git commit -m "Add special-mode expiry and mass-gain target columns"
```

---

## Task 2: Config, prompt files, `ConversationFlow` entry

**Files:**
- Create: `src/main/java/com/silporestockai/config/SpecialModeProperties.java`
- Create: `src/main/resources/prompts/meal-plan-gastritis-acute-system.txt`
- Create: `src/main/resources/prompts/meal-plan-gastritis-diet5-system.txt`
- Create: `src/main/resources/prompts/meal-plan-mass-gain-system.txt`
- Create: `src/main/resources/prompts/gastritis-intent-system.txt`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/silporestockai/model/ConversationFlow.java`
- Test: `src/test/java/com/silporestockai/config/SpecialModePropertiesTest.java`

**Interfaces:**
- Produces: `SpecialModeProperties(Duration gastritisAcuteDuration, Duration gastritisDiet5Duration, String sweepCron)` bound from `komora.special-mode.*`; `ConversationFlow.SPECIAL_MODE_SETUP`; four new prompt resources consumable via `@Value("classpath:prompts/...")`.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SpecialModePropertiesTest {

    @Autowired
    private SpecialModeProperties properties;

    @Test
    void bindsFromApplicationYmlDefaults() {
        assertThat(properties.gastritisAcuteDuration()).isEqualTo(Duration.ofDays(3));
        assertThat(properties.gastritisDiet5Duration()).isEqualTo(Duration.ofDays(11));
        assertThat(properties.sweepCron()).isEqualTo("0 0 * * * *");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*SpecialModePropertiesTest*"`
Expected: FAIL — `SpecialModeProperties` does not exist / is not a bean.

- [ ] **Step 3: Implement**

`config/SpecialModeProperties.java`:

```java
package com.silporestockai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long each stage of a durational special mode lasts, and how often the sweep looks for one that expired.
 *
 * @param gastritisAcuteDuration how long {@code MEDICAL_GASTRITIS_ACUTE} lasts before stepping down to
 *     {@code MEDICAL_DIET_TABLE_5}. A property, not a constant, so a demo can shrink it to seconds.
 * @param gastritisDiet5Duration how long {@code MEDICAL_DIET_TABLE_5} lasts before reverting to {@code NONE}.
 * @param sweepCron when the expiry sweep runs.
 */
@ConfigurationProperties(prefix = "komora.special-mode")
public record SpecialModeProperties(Duration gastritisAcuteDuration, Duration gastritisDiet5Duration, String sweepCron) {}
```

Register it: check whether the app enables `@ConfigurationPropertiesScan` globally (search `grep -rn "ConfigurationPropertiesScan\|@EnableConfigurationProperties" src/main/java/com/silporestockai/config/`). `CheckinProperties` is the precedent — follow whatever wiring already makes it an autowirable bean (likely `@ConfigurationPropertiesScan` on the main application class, meaning no extra step is needed; if instead `CheckinProperties` is registered via an explicit `@EnableConfigurationProperties(CheckinProperties.class)`, add `SpecialModeProperties.class` to the same annotation).

Append to `application.yml`, right after the existing `komora.checkin` block:

```yaml
  special-mode:
    gastritis-acute-duration: ${GASTRITIS_ACUTE_DURATION:3d}
    gastritis-diet5-duration: ${GASTRITIS_DIET5_DURATION:11d}
    sweep-cron: ${SPECIAL_MODE_SWEEP_CRON:0 0 * * * *}
```

Add to `ConversationFlow.java`, after `LIST_BUILDING`:

```java
    /** Collecting mass-gain parameters (weight, calorie/protein target) before generating that plan. */
    SPECIAL_MODE_SETUP
```

Prompt files, following `meal-plan-system.txt`'s style (Ukrainian, imperative rules, no explanatory prose in the answer):

`prompts/meal-plan-gastritis-acute-system.txt`:
```
Ти складаєш тижневе меню для людини з гострим гастритом (перші дні загострення). Пишеш українською.

Правила:
- Рівно 7 днів: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY. Кожен день рівно один раз.
- Мінімум 3 прийоми їжі на день: BREAKFAST, LUNCH, DINNER.
- Найсуворіший щадний режим: тільки відварені, парові або протерті страви. Ніякого смаження, копчення,
  маринадів, гострого, кислого, свіжих овочів і фруктів із шкіркою, газованих напоїв, кави, алкоголю,
  бобових, грибів, житнього хліба, наваристих бульйонів.
- Дозволено: слизові каші на воді, парові котлети з нежирного м'яса чи риби, відварені протерті овочі,
  кисіль, некруте какао, підсушений білий хліб.
- Кожна страва має назву та перелік інгредієнтів з кількістю та одиницею (кг, г, л, мл, шт), кожен
  інгредієнт має category — одну з: "Молочні продукти", "М'ясо і птиця", "Риба і морепродукти",
  "Овочі і фрукти", "Крупи і бакалія", "Хлібобулочні вироби", "Яйця", "Інше".
- Порції невеликі, часті прийоми їжі важливіші за об'єм.
- Ніяких коментарів, пояснень чи тексту поза структурою відповіді.
```

`prompts/meal-plan-gastritis-diet5-system.txt`:
```
Ти складаєш тижневе меню за принципами дієтичного столу №5 — щадний, але не такий суворий, як гострий
період гастриту. Пишеш українською.

Правила:
- Рівно 7 днів: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY. Кожен день рівно один раз.
- Мінімум 3 прийоми їжі на день: BREAKFAST, LUNCH, DINNER. SNACK можна додати.
- Відварені, тушковані або запечені страви без грубої скоринки. Овочі можна не протирати, якщо вони м'які.
- Уникай: смаженого, гострого, копченого, маринадів, свинини та іншого жирного м'яса, здобної випічки,
  шоколаду, газованих напоїв, алкоголю, кави натще.
- Дозволено ширше коло продуктів, ніж у гострій фазі: нежирне м'ясо і риба (не тільки парові), крупи,
  макарони, більшість овочів і фруктів у не надто кислому вигляді, кисломолочні продукти низької
  жирності, вчорашній хліб.
- Кожна страва має назву та перелік інгредієнтів з кількістю та одиницею (кг, г, л, мл, шт), кожен
  інгредієнт має category — одну з: "Молочні продукти", "М'ясо і птиця", "Риба і морепродукти",
  "Овочі і фрукти", "Крупи і бакалія", "Хлібобулочні вироби", "Яйця", "Інше".
- Ніяких коментарів, пояснень чи тексту поза структурою відповіді.
```

`prompts/meal-plan-mass-gain-system.txt`:
```
Ти складаєш тижневе меню для набору маси — високий калораж і високий білок. Пишеш українською.

Правила:
- Рівно 7 днів: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY. Кожен день рівно один раз.
- Мінімум 4 прийоми їжі на день: BREAKFAST, LUNCH, DINNER, SNACK — частіше й ситніше, ніж звичайне меню.
- Кожен прийом їжі має містити джерело білка (м'ясо, риба, яйця, сир, бобові) і калорійний гарнір
  (крупи, макарони, картопля, хліб).
- Використовуй калорійно щільні продукти: горіхи, олія, авокадо, сухофрукти, жирні молочні продукти —
  без надмірностей, але явно ситніше за звичайне меню.
- Якщо в профілі вказано цільову калорійність або білок на день, тримайся їх, а не загальних орієнтирів.
- Кожна страва має назву та перелік інгредієнтів з кількістю та одиницею (кг, г, л, мл, шт), кожен
  інгредієнт має category — одну з: "Молочні продукти", "М'ясо і птиця", "Риба і морепродукти",
  "Овочі і фрукти", "Крупи і бакалія", "Хлібобулочні вироби", "Яйця", "Інше".
- Ніяких коментарів, пояснень чи тексту поза структурою відповіді.
```

`prompts/gastritis-intent-system.txt` (used by Task 7's intent classifier):
```
Визнач, чи людина повідомляє про загострення гастриту або схожої проблеми зі шлунком і просить
дієтичний режим харчування. Це стосується лише реального повідомлення про хворобу зараз, а не
згадки хвороби в минулому, жарту чи запитання про дієту взагалі.

Поверни лише структуровану відповідь: isIllnessTrigger (true/false) та confidence (0..1).
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*SpecialModePropertiesTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/config/SpecialModeProperties.java \
        src/main/resources/prompts/meal-plan-gastritis-acute-system.txt \
        src/main/resources/prompts/meal-plan-gastritis-diet5-system.txt \
        src/main/resources/prompts/meal-plan-mass-gain-system.txt \
        src/main/resources/prompts/gastritis-intent-system.txt \
        src/main/resources/application.yml \
        src/main/java/com/silporestockai/model/ConversationFlow.java \
        src/test/java/com/silporestockai/config/SpecialModePropertiesTest.java
git commit -m "Add special-mode config, prompts, and SPECIAL_MODE_SETUP flow"
```

---

## Task 3: `MealPlanService` picks a special-mode system prompt

**Files:**
- Modify: `src/main/java/com/silporestockai/service/MealPlanService.java`
- Test: `src/test/java/com/silporestockai/integration/MealPlanIntegrationTest.java`

**Interfaces:**
- Consumes: `UserProfile.getSpecialMode()` (Task 1), the three new prompt resources (Task 2).
- Produces: no signature change — `generateWeeklyPlan`/`regenerateWithAdjustment` behave exactly as before when `specialMode` is `NONE`/`null`; when it is `MEDICAL_GASTRITIS_ACUTE`/`MEDICAL_DIET_TABLE_5`/`MASS_GAIN`, the corresponding system prompt is used instead of the recipe/ready-meals split, and `readyMealsOnly` is treated as `false` for that generation regardless of `cookingTimePreference` (a deliberate scope decision: combining a strict medical diet with the ready-meals-catalog-only path is out of scope for this feature).

- [ ] **Step 1: Write the failing test**

Add to `MealPlanIntegrationTest.java`, after `sendsTheProfileConstraintsToClaude`:

```java
    @Test
    void usesTheGastritisAcutePromptWhenTheProfileIsInThatSpecialMode() {
        UUID userId = profiledUser(8108L, List.of(), List.of());
        userProfileRepository.findByUserId(userId).ifPresent(profile -> {
            profile.setSpecialMode(com.silporestockai.model.SpecialMode.MEDICAL_GASTRITIS_ACUTE);
            userProfileRepository.save(profile);
        });
        CLAUDE.respondWithText(fullWeekJson());

        mealPlanService.regenerateWithAdjustment(userId, null);

        String sent = CLAUDE.requests().getFirst().toString();
        assertThat(sent).contains("гострим гастритом");
    }

    @Test
    void usesTheNormalPromptWhenSpecialModeIsNone() {
        UUID userId = profiledUser(8109L, List.of(), List.of());
        CLAUDE.respondWithText(fullWeekJson());

        mealPlanService.generateWeeklyPlan(userId);

        String sent = CLAUDE.requests().getFirst().toString();
        assertThat(sent).doesNotContain("гострим гастритом");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*MealPlanIntegrationTest*"`
Expected: FAIL on `usesTheGastritisAcutePromptWhenTheProfileIsInThatSpecialMode` — the sent request never contains "гострим гастритом" because the system prompt is not yet selected by `specialMode`.

- [ ] **Step 3: Implement**

In `MealPlanService.java`, add three more constructor `Resource` params and fields (after `readyMealsSystemPromptResource`):

```java
            @Value("classpath:prompts/meal-plan-gastritis-acute-system.txt") Resource gastritisAcuteSystemPromptResource,
            @Value("classpath:prompts/meal-plan-gastritis-diet5-system.txt") Resource gastritisDiet5SystemPromptResource,
            @Value("classpath:prompts/meal-plan-mass-gain-system.txt") Resource massGainSystemPromptResource,
```
and in the constructor body:
```java
        this.gastritisAcuteSystemPrompt = read(gastritisAcuteSystemPromptResource);
        this.gastritisDiet5SystemPrompt = read(gastritisDiet5SystemPromptResource);
        this.massGainSystemPrompt = read(massGainSystemPromptResource);
```
with matching `private final String gastritisAcuteSystemPrompt;` etc. fields next to `readyMealsSystemPrompt`.

Replace the two lines at the top of `generate(...)`:
```java
        boolean readyMealsOnly = profile.getCookingTimePreference() == CookingTimePreference.READY_MEALS_ONLY;
        String systemPrompt = readyMealsOnly ? readyMealsSystemPrompt : recipeSystemPrompt;
```
with:
```java
        String specialPrompt = specialSystemPromptFor(profile.getSpecialMode());
        boolean readyMealsOnly =
                specialPrompt == null && profile.getCookingTimePreference() == CookingTimePreference.READY_MEALS_ONLY;
        String systemPrompt = specialPrompt != null ? specialPrompt : (readyMealsOnly ? readyMealsSystemPrompt : recipeSystemPrompt);
```

Add the helper near `describe(...)`:
```java
    /** {@code null} for {@code NONE}/{@code BLACKOUT} (blackout never reaches this service) — falls back to the usual recipe/ready-meals split. */
    private String specialSystemPromptFor(SpecialMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case MEDICAL_GASTRITIS_ACUTE -> gastritisAcuteSystemPrompt;
            case MEDICAL_DIET_TABLE_5 -> gastritisDiet5SystemPrompt;
            case MASS_GAIN -> massGainSystemPrompt;
            default -> null;
        };
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*MealPlanIntegrationTest*"`
Expected: PASS, all tests in the class including the two new ones.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/MealPlanService.java \
        src/test/java/com/silporestockai/integration/MealPlanIntegrationTest.java
git commit -m "Select a special-mode system prompt in MealPlanService"
```

---

## Task 4: `UserProfileRepository` expired-special-mode query

**Files:**
- Modify: `src/main/java/com/silporestockai/repository/UserProfileRepository.java`
- Test: `src/test/java/com/silporestockai/integration/UserProfileSpecialModeFieldsIntegrationTest.java` (the file created in Task 1)

**Interfaces:**
- Produces: `List<UserProfile> findAllWithExpiredSpecialMode(Instant now)` — Task 6's `SpecialModeService.sweepExpired()` consumes this.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void findsOnlyProfilesWithAnExpiredSpecialMode() {
        User due = userAccountService.findOrCreate(9002L);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(due.getId())
                .specialMode(SpecialMode.MEDICAL_GASTRITIS_ACUTE)
                .specialModeExpiresAt(Instant.now().minusSeconds(60))
                .build());
        User notYetDue = userAccountService.findOrCreate(9003L);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(notYetDue.getId())
                .specialMode(SpecialMode.MASS_GAIN)
                .specialModeExpiresAt(Instant.now().plusSeconds(3600))
                .build());
        User noExpiry = userAccountService.findOrCreate(9004L);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(noExpiry.getId())
                .specialMode(SpecialMode.MASS_GAIN)
                .build());

        List<UserProfile> expired = userProfileRepository.findAllWithExpiredSpecialMode(Instant.now());

        assertThat(expired).extracting(UserProfile::getUserId).containsExactly(due.getId());
    }
```
(add `import java.util.List;` and `import com.silporestockai.model.SpecialMode;` to the test file if not already present from Task 1's imports.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*UserProfileSpecialModeFieldsIntegrationTest*"`
Expected: FAIL — no method `findAllWithExpiredSpecialMode`.

- [ ] **Step 3: Implement**

```java
    @Query("select p from UserProfile p where p.specialModeExpiresAt is not null and p.specialModeExpiresAt <= :now")
    List<UserProfile> findAllWithExpiredSpecialMode(@Param("now") Instant now);
```
Add `import java.time.Instant;`, `import java.util.List;`, `import org.springframework.data.jpa.repository.Query;`, `import org.springframework.data.repository.query.Param;` to `UserProfileRepository.java`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*UserProfileSpecialModeFieldsIntegrationTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/repository/UserProfileRepository.java \
        src/test/java/com/silporestockai/integration/UserProfileSpecialModeFieldsIntegrationTest.java
git commit -m "Add UserProfileRepository.findAllWithExpiredSpecialMode"
```

---

## Task 5: `SpecialModeService` — trigger gastritis, cancel, toggle UA-only, guard against stacking

**Files:**
- Create: `src/main/java/com/silporestockai/service/SpecialModeService.java`
- Test: `src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java`

**Interfaces:**
- Consumes: `MealPlanService.regenerateWithAdjustment(UUID, String)` (existing), `ShoppingListService.deriveFromMealPlan(UUID, ShoppingListSourceType)` (existing), `ShoppingListBuilderService.present(User, List<ShoppingListItem>)` (existing), `UserProfileRepository.findByUserId` (existing), `TelegramOutboundService.sendMessage(long, String)` (existing).
- Produces: `void triggerGastritis(User user)`, `void cancel(User user)`, `void toggleUaOnly(User user)` — Task 8/9 wire these to commands; Task 6 adds `sweepExpired()` to this same class.

- [ ] **Step 1: Write the failing test**

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.BasketItem;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.SpecialModeService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
import com.silporestockai.support.StubTelegramServer;
import com.silporestockai.utils.TokenCipher;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DisplayName("SpecialModeService switches and cancels a special mode without touching the baseline")
class SpecialModeIntegrationTest extends AbstractIntegrationTest {

    private static final long CHAT_ID = 9101L;
    private static final StubTelegramServer TELEGRAM = startTelegram();
    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private SpecialModeService specialModeService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private User user;

    private static StubTelegramServer startTelegram() {
        try {
            return new StubTelegramServer("9101:stub-bot-token");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products"));
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
        registry.add("telegram.bot-token", () -> "9101:stub-bot-token");
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
        baselineBasketRepository.deleteAll();
        userProfileRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();

        user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .build());
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(new BasketItem("p-1", "Гречка", "кг", BigDecimal.ONE, new BigDecimal("48"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());
        scriptSilpo();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());
    }

    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-s\"}");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool(
                "silpo_find_products_batch",
                "{\"queries\":[{\"query\":\"вівсяні пластівці\",\"products\":[{\"name\":\"вівсяні пластівці\","
                        + "\"productId\":\"p-90\",\"branchId\":\"branch-9\"}]}]}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-s","branchId":"branch-9","companyId":"company-1","deliveryType":"delivery",\
                "items":[{"productId":"p-90","name":"Вівсянка","unit":"шт","quantity":1,"price":45}],\
                "total":45,"validations":[],\
                "checkoutWebLink":"https://silpo.ua/checkout/cart-s"}""");
    }

    @Test
    void triggeringGastritisSetsTheModeAndGeneratesAMedicalPlan() {
        specialModeService.triggerGastritis(user);

        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getSpecialMode()).isEqualTo(SpecialMode.MEDICAL_GASTRITIS_ACUTE);
        assertThat(profile.getSpecialModeStartedAt()).isNotNull();
        assertThat(profile.getSpecialModeExpiresAt()).isNotNull();
        assertThat(CLAUDE.requests().getFirst().toString()).contains("гострим гастритом");
    }

    @Test
    void triggeringGastritisLeavesTheBaselineExactlyAsItWas() {
        UUID baselineBefore = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow()
                .getId();

        specialModeService.triggerGastritis(user);

        assertThat(baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(user.getId())
                        .orElseThrow()
                        .getId())
                .isEqualTo(baselineBefore);
        assertThat(baselineBasketRepository.findByUserIdOrderByConfirmedAtDesc(user.getId()))
                .hasSize(1);
    }

    @Test
    void refusesToTriggerAgainWhileAModeIsAlreadyActive() {
        specialModeService.triggerGastritis(user);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        specialModeService.triggerGastritis(user);

        assertThat(CLAUDE.callCount()).isZero();
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("вже активний");
    }

    @Test
    void cancelRevertsToNormalAndRegeneratesANormalPlan() {
        specialModeService.triggerGastritis(user);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        specialModeService.cancel(user);

        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getSpecialMode()).isEqualTo(SpecialMode.NONE);
        assertThat(profile.getSpecialModeExpiresAt()).isNull();
        assertThat(CLAUDE.requests().getFirst().toString()).doesNotContain("гострим гастритом");
    }

    @Test
    void cancelWhenNothingIsActiveJustSaysSo() {
        specialModeService.cancel(user);

        assertThat(CLAUDE.callCount()).isZero();
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("Звичайний режим і так активний");
    }

    @Test
    void toggleUaOnlyFlipsIndependentlyOfSpecialMode() {
        specialModeService.toggleUaOnly(user);
        assertThat(userProfileRepository.findByUserId(user.getId()).orElseThrow().getOnlyUaProducer())
                .isTrue();

        specialModeService.toggleUaOnly(user);
        assertThat(userProfileRepository.findByUserId(user.getId()).orElseThrow().getOnlyUaProducer())
                .isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: FAIL — `SpecialModeService` does not exist.

- [ ] **Step 3: Implement**

```java
package com.silporestockai.service;

import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.service.telegram.TelegramOutboundService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every {@code special_mode}/{@code only_ua_producer} transition: the gastritis two-stage cycle, mass gain,
 * UA-only, and the {@code /normal} early exit.
 *
 * <p>Every regeneration reuses the exact pipeline a normal weekly plan takes ({@link MealPlanService} →
 * {@link ShoppingListService#deriveFromMealPlan} → {@link ShoppingListBuilderService#present}), the same one
 * {@link MealPlanHandoffService#generateFirstPlan} uses. That is what keeps {@code BaselineBasket} safe without a
 * snapshot/restore mechanism: {@link ShoppingListBuilderService#order()} only ever stores a baseline for
 * {@code OrderType.INITIAL}, and a household already using special modes already has one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpecialModeService {

    private final UserProfileRepository userProfileRepository;
    private final MealPlanService mealPlanService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final TelegramOutboundService telegramOutboundService;
    private final com.silporestockai.config.SpecialModeProperties specialModeProperties;
    private final Clock clock;

    @Transactional
    public void triggerGastritis(User user) {
        UserProfile profile = requireProfile(user);
        if (isActive(profile)) {
            telegramOutboundService.sendMessage(
                    user.getTelegramChatId(), "У вас вже активний інший режим харчування. Спершу завершіть його: /normal.");
            return;
        }
        Instant now = clock.instant();
        profile.setSpecialMode(SpecialMode.MEDICAL_GASTRITIS_ACUTE);
        profile.setSpecialModeStartedAt(now);
        profile.setSpecialModeExpiresAt(now.plus(specialModeProperties.gastritisAcuteDuration()));
        userProfileRepository.save(profile);
        log.info("user {} entered MEDICAL_GASTRITIS_ACUTE, expires {}", user.getId(), profile.getSpecialModeExpiresAt());
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                "Розумію, гастрит. Перемикаю на щадне харчування — складаю новий план.");
        regenerateAndPresent(user);
    }

    @Transactional
    public void cancel(User user) {
        UserProfile profile = requireProfile(user);
        if (!isActive(profile)) {
            telegramOutboundService.sendMessage(user.getTelegramChatId(), "Звичайний режим і так активний.");
            return;
        }
        revertToNormal(user, profile);
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(), "Повернув звичайний раціон — складаю новий план.");
        regenerateAndPresent(user);
    }

    @Transactional
    public void toggleUaOnly(User user) {
        UserProfile profile = requireProfile(user);
        boolean next = !Boolean.TRUE.equals(profile.getOnlyUaProducer());
        profile.setOnlyUaProducer(next);
        userProfileRepository.save(profile);
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                next
                        ? "Тепер шукатиму переважно товари українського виробництва."
                        : "Прибрав обмеження на українського виробника.");
    }

    /** Fields cleared, so a later {@link #isActive} check and the expiry sweep both see a clean NONE state. */
    void revertToNormal(User user, UserProfile profile) {
        profile.setSpecialMode(SpecialMode.NONE);
        profile.setSpecialModeStartedAt(null);
        profile.setSpecialModeExpiresAt(null);
        userProfileRepository.save(profile);
        log.info("user {} reverted to NONE", user.getId());
    }

    private void regenerateAndPresent(User user) {
        MealPlan plan = mealPlanService.regenerateWithAdjustment(user.getId(), null);
        List<ShoppingListItem> items = shoppingListService.deriveFromMealPlan(plan.getId(), plan.getSourceType());
        shoppingListBuilderService.present(user, items);
    }

    private static boolean isActive(UserProfile profile) {
        return profile.getSpecialMode() != null && profile.getSpecialMode() != SpecialMode.NONE;
    }

    private UserProfile requireProfile(User user) {
        return userProfileRepository
                .findByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("user %s has no profile yet".formatted(user.getId())));
    }
}
```

Note: `MealPlanService.generate(...)` currently reads `profile.getSpecialMode()` fresh from the passed-in `UserProfile` it loads itself via `userProfileRepository.findByUserId(userId)` — since `triggerGastritis` saves the profile before calling `regenerateAndPresent`, `MealPlanService` sees the updated `specialMode`. No transactional-visibility issue: `@Transactional` on `triggerGastritis` and the `save(...)` flushes before `MealPlanService`'s own `findByUserId` read within the same transaction (JPA read-your-writes).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/SpecialModeService.java \
        src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java
git commit -m "Add SpecialModeService: trigger gastritis, cancel, toggle UA-only"
```

---

## Task 6: Two-stage expiry sweep + `SpecialModeScheduler`

**Files:**
- Modify: `src/main/java/com/silporestockai/service/SpecialModeService.java`
- Create: `src/main/java/com/silporestockai/job/SpecialModeScheduler.java`
- Modify: `src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java`

**Interfaces:**
- Produces: `int sweepExpired()` on `SpecialModeService`; `SpecialModeScheduler.sweepExpiredSpecialModes()` — no return value, delegates.

- [ ] **Step 1: Write the failing test**

Add to `SpecialModeIntegrationTest.java`:

```java
    @Test
    void sweepTransitionsAcuteToDietTable5WhenTheAcuteDurationHasPassed() {
        specialModeService.triggerGastritis(user);
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        // Fast-forward: matches the "backdate the timestamp" convention CheckinPromptIntegrationTest uses instead
        // of an injected fake Clock.
        profile.setSpecialModeStartedAt(Instant.now().minusSeconds(1_000_000));
        profile.setSpecialModeExpiresAt(Instant.now().minusSeconds(1));
        userProfileRepository.save(profile);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        int swept = specialModeService.sweepExpired();

        assertThat(swept).isEqualTo(1);
        UserProfile after = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(after.getSpecialMode()).isEqualTo(SpecialMode.MEDICAL_DIET_TABLE_5);
        assertThat(after.getSpecialModeExpiresAt()).isAfter(Instant.now());
        assertThat(CLAUDE.requests().getFirst().toString()).contains("столу №5")
                .doesNotContain("гострим гастритом");
        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("дієтичного столу №5");
    }

    @Test
    void sweepRevertsToNormalWhenDietTable5HasAlsoExpired() {
        specialModeService.triggerGastritis(user);
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        profile.setSpecialMode(SpecialMode.MEDICAL_DIET_TABLE_5);
        profile.setSpecialModeExpiresAt(Instant.now().minusSeconds(1));
        userProfileRepository.save(profile);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        int swept = specialModeService.sweepExpired();

        assertThat(swept).isEqualTo(1);
        UserProfile after = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(after.getSpecialMode()).isEqualTo(SpecialMode.NONE);
        assertThat(after.getSpecialModeExpiresAt()).isNull();
        assertThat(TELEGRAM.sentMessages().getLast().toString())
                .contains("Два тижні дієтичного харчування завершено, повертаємось до звичайного раціону");
    }

    @Test
    void sweepingTwiceInARowDoesNothingTheSecondTime() {
        specialModeService.triggerGastritis(user);
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        profile.setSpecialModeExpiresAt(Instant.now().minusSeconds(1));
        userProfileRepository.save(profile);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        specialModeService.sweepExpired();
        CLAUDE.reset();
        int secondSweep = specialModeService.sweepExpired();

        assertThat(secondSweep).isZero();
        assertThat(CLAUDE.callCount()).isZero();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: FAIL — no `sweepExpired()` method.

- [ ] **Step 3: Implement**

Add to `SpecialModeService.java` (needs `UserProfileRepository.findAllWithExpiredSpecialMode` from Task 4 and `UserRepository` to resolve `User` from `UserProfile.userId` — inject `UserRepository userRepository` as a new constructor dependency):

```java
    /**
     * Advances every user whose current stage expired: ACUTE steps down to DIET_TABLE_5 with a fresh expiry;
     * anything else at expiry (DIET_TABLE_5, or any other durational mode reaching its own expiry) reverts to
     * NONE. One user's failure is logged and skipped, matching {@link CheckinPromptService#sweep()}'s convention.
     */
    @Transactional
    public int sweepExpired() {
        List<UserProfile> due = userProfileRepository.findAllWithExpiredSpecialMode(clock.instant());
        int handled = 0;
        for (UserProfile profile : due) {
            try {
                userRepository.findById(profile.getUserId()).ifPresent(user -> {
                    if (profile.getSpecialMode() == SpecialMode.MEDICAL_GASTRITIS_ACUTE) {
                        stepDownToDietTable5(user, profile);
                    } else {
                        revertToNormal(user, profile);
                        telegramOutboundService.sendMessage(
                                user.getTelegramChatId(),
                                "Два тижні дієтичного харчування завершено, повертаємось до звичайного раціону.");
                        regenerateAndPresent(user);
                    }
                });
                handled++;
            } catch (RuntimeException e) {
                log.error("could not advance special mode for profile {}", profile.getId(), e);
            }
        }
        log.info("special-mode sweep: {} of {} expired profiles advanced", handled, due.size());
        return handled;
    }

    private void stepDownToDietTable5(User user, UserProfile profile) {
        Instant now = clock.instant();
        profile.setSpecialMode(SpecialMode.MEDICAL_DIET_TABLE_5);
        profile.setSpecialModeExpiresAt(profile.getSpecialModeStartedAt()
                .plus(specialModeProperties.gastritisAcuteDuration())
                .plus(specialModeProperties.gastritisDiet5Duration()));
        userProfileRepository.save(profile);
        log.info("user {} stepped down to MEDICAL_DIET_TABLE_5, expires {}", user.getId(), profile.getSpecialModeExpiresAt());
        telegramOutboundService.sendMessage(
                user.getTelegramChatId(),
                "Гострий період завершено, переходимо до дієтичного столу №5 ще на кілька днів.");
        regenerateAndPresent(user);
    }
```

Add `import java.util.List;` if not already present, `import com.silporestockai.repository.UserRepository;`, and `private final UserRepository userRepository;` as a constructor field (Lombok `@RequiredArgsConstructor` picks it up automatically from field order).

`job/SpecialModeScheduler.java`:

```java
package com.silporestockai.job;

import com.silporestockai.service.SpecialModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the special-mode expiry sweep on a clock. Nothing but the trigger lives here — {@code SpecialModeService}
 * decides everything, and a test can call it directly instead of waiting for a cron to come round.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpecialModeScheduler {

    private final SpecialModeService specialModeService;

    @Scheduled(cron = "${komora.special-mode.sweep-cron}")
    public void sweepExpiredSpecialModes() {
        specialModeService.sweepExpired();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/SpecialModeService.java \
        src/main/java/com/silporestockai/job/SpecialModeScheduler.java \
        src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java
git commit -m "Add two-stage gastritis expiry sweep and SpecialModeScheduler"
```

---

## Task 7: Gastritis free-text intent trigger

**Files:**
- Modify: `src/main/java/com/silporestockai/service/SpecialModeService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Test: `src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java`

**Interfaces:**
- Consumes: `ClaudeApiClient.completeStructured(String, String, Class<T>)` (existing).
- Produces: `boolean detectGastritisIntent(String text)` on `SpecialModeService`, consumed by `TelegramRoutingService`'s fallback branch.

- [ ] **Step 1: Write the failing test**

Add to `SpecialModeIntegrationTest.java` (note: this test drives the flow through the webhook, so it needs `MockMvc`; add `@Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;` to the class):

```java
    private void sendText(int updateId, String text) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/telegram/webhook")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"update_id":%d,"message":{"message_id":%d,"date":1,\
                                "chat":{"id":%d,"type":"private"},"from":{"id":5,"is_bot":false,"first_name":"Тест"},\
                                "text":"%s"}}""".formatted(updateId, updateId, CHAT_ID, text)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void freeTextAboutGastritisTriggersTheMedicalMode() throws Exception {
        CLAUDE.respondWithTexts(
                "{\"isIllnessTrigger\":true,\"confidence\":0.95}", MealPlanIntegrationTest.fullWeekJson());

        sendText(1, "я захворів, гастрит, два тижні дієтичного раціону");

        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getSpecialMode()).isEqualTo(SpecialMode.MEDICAL_GASTRITIS_ACUTE);
    }

    @Test
    void unrelatedFreeTextDoesNotTriggerAnything() throws Exception {
        CLAUDE.respondWithText("{\"isIllnessTrigger\":false,\"confidence\":0.9}");

        sendText(1, "що там на вечерю сьогодні?");

        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getSpecialMode()).isNotEqualTo(SpecialMode.MEDICAL_GASTRITIS_ACUTE);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: FAIL — free text still falls through to the generic "pick an action" message; `specialMode` stays unset.

- [ ] **Step 3: Implement**

Add a `record`/DTO and method to `SpecialModeService.java`:

```java
    private static final String GASTRITIS_INTENT_SYSTEM_PROMPT_RESOURCE = "classpath:prompts/gastritis-intent-system.txt";
```

Add a `@Value` constructor param `Resource gastritisIntentSystemPromptResource` and field `gastritisIntentSystemPrompt` (same `read(...)` helper pattern `MealPlanService` uses — add a private static `read(Resource)` method to `SpecialModeService`, copied from `MealPlanService`'s, or extract one if a shared utility already exists — check `grep -rn "private static String read(Resource" src/main/java/com/silporestockai/service/` first and reuse if a common helper exists; otherwise duplicate, matching the existing per-class convention seen in `MealPlanService`/`ShoppingListBuilderService`).

```java
    public boolean detectGastritisIntent(String text) {
        try {
            GastritisIntent intent =
                    claudeApiClient.completeStructured(gastritisIntentSystemPrompt, text, GastritisIntent.class);
            return intent != null && intent.isIllnessTrigger() && intent.confidence() >= 0.7;
        } catch (RuntimeException e) {
            log.warn("could not classify gastritis intent for text, treating as no match", e);
            return false;
        }
    }

    private record GastritisIntent(boolean isIllnessTrigger, double confidence) {}
```

Because `@RequiredArgsConstructor` cannot express the `@Value` resource param cleanly, drop `@RequiredArgsConstructor` from the class and write the constructor by hand — same pattern `MealPlanService`/`ShoppingListBuilderService` use for this exact reason. Add imports `com.silporestockai.client.claude.ClaudeApiClient`, `org.springframework.beans.factory.annotation.Value`, `org.springframework.core.io.Resource`, `java.io.IOException`, `java.io.UncheckedIOException`, `java.nio.charset.StandardCharsets`, plus a `private static String read(Resource resource)` helper copied verbatim from `MealPlanService`'s (no shared utility exists yet — each class keeps its own, matching that established convention). The full field list and constructor, replacing everything from `private final UserProfileRepository userProfileRepository;` down through the constructor:

```java
    private final UserProfileRepository userProfileRepository;
    private final UserRepository userRepository;
    private final MealPlanService mealPlanService;
    private final ShoppingListService shoppingListService;
    private final ShoppingListBuilderService shoppingListBuilderService;
    private final TelegramOutboundService telegramOutboundService;
    private final ClaudeApiClient claudeApiClient;
    private final SpecialModeProperties specialModeProperties;
    private final Clock clock;
    private final String gastritisIntentSystemPrompt;

    public SpecialModeService(
            UserProfileRepository userProfileRepository,
            UserRepository userRepository,
            MealPlanService mealPlanService,
            ShoppingListService shoppingListService,
            ShoppingListBuilderService shoppingListBuilderService,
            TelegramOutboundService telegramOutboundService,
            ClaudeApiClient claudeApiClient,
            SpecialModeProperties specialModeProperties,
            Clock clock,
            @Value("classpath:prompts/gastritis-intent-system.txt") Resource gastritisIntentSystemPromptResource) {
        this.userProfileRepository = userProfileRepository;
        this.userRepository = userRepository;
        this.mealPlanService = mealPlanService;
        this.shoppingListService = shoppingListService;
        this.shoppingListBuilderService = shoppingListBuilderService;
        this.telegramOutboundService = telegramOutboundService;
        this.claudeApiClient = claudeApiClient;
        this.specialModeProperties = specialModeProperties;
        this.clock = clock;
        this.gastritisIntentSystemPrompt = read(gastritisIntentSystemPromptResource);
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the gastritis intent system prompt", e);
        }
    }
```
Use fully-qualified `com.silporestockai.config.SpecialModeProperties` or add the import — Task 5 referenced it inline as `com.silporestockai.config.SpecialModeProperties specialModeProperties`; switch that field's type to the plain `SpecialModeProperties` name with a proper import now that the class has a real import list to maintain.

In `TelegramRoutingService.java`, replace the final fallback block:
```java
        telegramOutboundService.sendMessageWithMainMenu(
                incoming.chatId(),
                "Профіль уже є. Обери дію нижче або напиши /list, /reorder, /voice, /blackout чи /calendar.");
    }
```
with:
```java
        if (incoming instanceof TelegramIncomingUpdate.Text freeText
                && specialModeService.detectGastritisIntent(freeText.text())) {
            specialModeService.triggerGastritis(user);
            return;
        }
        telegramOutboundService.sendMessageWithMainMenu(
                incoming.chatId(),
                "Профіль уже є. Обери дію нижче або напиши /list, /reorder, /voice, /blackout чи /calendar.");
    }
```
Add `private final SpecialModeService specialModeService;` to the field list and `import com.silporestockai.service.SpecialModeService;`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/SpecialModeService.java \
        src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java \
        src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java
git commit -m "Detect gastritis intent from free text and trigger the medical mode"
```

---

## Task 8: `/normal` and `/uaonly` commands

**Files:**
- Modify: `src/main/java/com/silporestockai/service/telegram/MainMenuKeyboard.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Test: `src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java`

**Interfaces:**
- Consumes: `SpecialModeService.cancel(User)`, `SpecialModeService.toggleUaOnly(User)` (Task 5).

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void normalCommandCancelsAnActiveSpecialMode() throws Exception {
        specialModeService.triggerGastritis(user);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        sendText(2, "/normal");

        assertThat(userProfileRepository.findByUserId(user.getId()).orElseThrow().getSpecialMode())
                .isEqualTo(SpecialMode.NONE);
    }

    @Test
    void uaonlyCommandTogglesTheFlag() throws Exception {
        sendText(1, "/uaonly");

        assertThat(userProfileRepository.findByUserId(user.getId()).orElseThrow().getOnlyUaProducer())
                .isTrue();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: FAIL — `/normal` and `/uaonly` fall through to the gastritis-intent check (a false classification, since `CLAUDE` has no scripted response queued for it in these tests, which will fail the test setup) and/or the generic fallback message; profile fields stay unchanged.

- [ ] **Step 3: Implement**

`MainMenuKeyboard.java`: add `public static final String NORMAL = "↩️ Звичайний режим";` and, in `markup()`, this plan keeps the existing 3-row layout unchanged — `/normal` and `/uaonly` are reachable by typed command only (matches the design's "not added to the fixed keyboard, to keep it uncluttered" decision), so no `markup()` change is needed, just the constant for `matches(...)` to reference.

In `TelegramRoutingService.java`, add two command blocks in the same position/style as `/blackout` (before the free-text gastritis-intent check added in Task 7):

```java
        if (incoming instanceof TelegramIncomingUpdate.Text normal
                && matches(normal.text(), "/normal", MainMenuKeyboard.NORMAL)) {
            specialModeService.cancel(user);
            return;
        }
        if (incoming instanceof TelegramIncomingUpdate.Text uaOnly && matches(uaOnly.text(), "/uaonly", "")) {
            specialModeService.toggleUaOnly(user);
            return;
        }
```
(`/uaonly` has no main-menu button, so `matches(text, "/uaonly", "")` — an empty label never equals a real message, only the `startsWith("/uaonly")` branch can match, which is the intended typed-only entry point.)

Update the trailing fallback hint text to mention the new commands:
```java
                "Профіль уже є. Обери дію нижче або напиши /list, /reorder, /voice, /blackout, /calendar, "
                        + "/masgain, /uaonly чи /normal.");
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/telegram/MainMenuKeyboard.java \
        src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java \
        src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java
git commit -m "Add /normal and /uaonly commands"
```

---

## Task 9: Mass gain parameter-collection flow + `/masgain`

**Files:**
- Modify: `src/main/java/com/silporestockai/service/SpecialModeService.java`
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java`
- Test: `src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java`

**Interfaces:**
- Consumes: `ConversationStateService.load/save` (existing), `ConversationFlow.SPECIAL_MODE_SETUP` (Task 2).
- Produces: `void startMassGainSetup(User user)`, `void handle(User user, TelegramIncomingUpdate incoming)` on `SpecialModeService` — `TelegramRoutingService`'s flow-gate dispatches to it while `SPECIAL_MODE_SETUP` is active, mirroring how `CHECK_IN`/`LIST_BUILDING` dispatch to their flow services.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void masgainCollectsParametersThenGeneratesAHigherCaloriePlan() throws Exception {
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson());

        sendText(1, "/masgain");
        sendText(2, "82.5");
        sendText(3, "3200");
        sendText(4, "160");

        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(profile.getSpecialMode()).isEqualTo(SpecialMode.MASS_GAIN);
        assertThat(profile.getTargetWeightKg()).isEqualByComparingTo("82.5");
        assertThat(profile.getTargetCalories()).isEqualTo(3200);
        assertThat(profile.getTargetProteinG()).isEqualTo(160);
        assertThat(CLAUDE.requests().getLast().toString()).contains("набору маси");
    }

    @Test
    void masgainRefusesToStartWhileAnotherModeIsActive() throws Exception {
        specialModeService.triggerGastritis(user);
        CLAUDE.reset();

        sendText(2, "/masgain");

        assertThat(TELEGRAM.sentMessages().getLast().toString()).contains("вже активний");
        assertThat(CLAUDE.callCount()).isZero();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: FAIL — `/masgain` is unrecognized, falls through to the fallback/gastritis-intent path.

- [ ] **Step 3: Implement**

Add `private final ConversationStateService conversationStateService;` to the field list (after `clock`, before `gastritisIntentSystemPrompt`) and a matching `ConversationStateService conversationStateService` constructor parameter (same position) plus `this.conversationStateService = conversationStateService;` in the constructor body written out in Task 7 — `ConversationStateService` already lives in the `service` package (see `ConversationStateService.java`), so it needs an import (`com.silporestockai.service.ConversationStateService` — or none, if `SpecialModeService` is in the same package, which it is: no import needed, just reference the type).

Add to `SpecialModeService.java`:

```java
    private static final String STEP_ASK_WEIGHT = "ASK_WEIGHT";
    private static final String STEP_ASK_CALORIES = "ASK_CALORIES";
    private static final String STEP_ASK_PROTEIN = "ASK_PROTEIN";
    private static final String KEY_WEIGHT = "weightKg";
    private static final String KEY_CALORIES = "targetCalories";

    public void startMassGainSetup(User user) {
        UserProfile profile = requireProfile(user);
        if (isActive(profile)) {
            telegramOutboundService.sendMessage(
                    user.getTelegramChatId(), "У вас вже активний інший режим харчування. Спершу завершіть його: /normal.");
            return;
        }
        conversationStateService.save(
                user.getTelegramChatId(), ConversationFlow.SPECIAL_MODE_SETUP, STEP_ASK_WEIGHT, Map.of());
        telegramOutboundService.sendMessage(user.getTelegramChatId(), "Набір маси. Яка зараз вага, кг?");
    }

    /** Everything a chat sitting in {@link ConversationFlow#SPECIAL_MODE_SETUP} can send. */
    public void handle(User user, TelegramIncomingUpdate incoming) {
        long chatId = incoming.chatId();
        if (!(incoming instanceof TelegramIncomingUpdate.Text text)) {
            telegramOutboundService.sendMessage(chatId, "Напиши, будь ласка, число.");
            return;
        }
        ConversationState state = conversationStateService.load(chatId);
        BigDecimal number;
        try {
            number = new BigDecimal(text.text().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            telegramOutboundService.sendMessage(chatId, "Не зрозумів число, спробуй ще раз.");
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>(state.getContext());
        switch (state.getCurrentStep()) {
            case STEP_ASK_WEIGHT -> {
                context.put(KEY_WEIGHT, number.toPlainString());
                conversationStateService.save(chatId, ConversationFlow.SPECIAL_MODE_SETUP, STEP_ASK_CALORIES, context);
                telegramOutboundService.sendMessage(chatId, "Скільки калорій на день — ціль?");
            }
            case STEP_ASK_CALORIES -> {
                context.put(KEY_CALORIES, number.intValue());
                conversationStateService.save(chatId, ConversationFlow.SPECIAL_MODE_SETUP, STEP_ASK_PROTEIN, context);
                telegramOutboundService.sendMessage(chatId, "Скільки грамів білка на день — ціль?");
            }
            case STEP_ASK_PROTEIN -> {
                finishMassGainSetup(user, context, number.intValue());
                conversationStateService.save(chatId, ConversationFlow.NONE, null, Map.of());
            }
            default -> telegramOutboundService.sendMessage(chatId, "Напиши /masgain, щоб почати заново.");
        }
    }

    private void finishMassGainSetup(User user, Map<String, Object> context, int targetProteinG) {
        UserProfile profile = requireProfile(user);
        Instant now = clock.instant();
        profile.setSpecialMode(SpecialMode.MASS_GAIN);
        profile.setSpecialModeStartedAt(now);
        profile.setTargetWeightKg(new BigDecimal(context.get(KEY_WEIGHT).toString()));
        profile.setTargetCalories(Integer.parseInt(context.get(KEY_CALORIES).toString()));
        profile.setTargetProteinG(targetProteinG);
        userProfileRepository.save(profile);
        log.info("user {} entered MASS_GAIN", user.getId());
        telegramOutboundService.sendMessage(user.getTelegramChatId(), "Готую план для набору маси.");
        regenerateAndPresent(user);
    }
```

Add imports: `com.silporestockai.entity.ConversationState`, `com.silporestockai.model.ConversationFlow`, `com.silporestockai.model.TelegramIncomingUpdate`, `java.math.BigDecimal`, `java.util.LinkedHashMap`, `java.util.Map`.

In `MealPlanService.describe(...)`, thread the mass-gain targets into the prompt text so the model actually sees them (append after the `specialMode` block, around line 342):
```java
        if (profile.getSpecialMode() == SpecialMode.MASS_GAIN) {
            if (profile.getTargetCalories() != null) {
                text.append("Цільова калорійність на день: ").append(profile.getTargetCalories()).append(" ккал\n");
            }
            if (profile.getTargetProteinG() != null) {
                text.append("Цільовий білок на день: ").append(profile.getTargetProteinG()).append(" г\n");
            }
        }
```

In `TelegramRoutingService.java`, add `SPECIAL_MODE_SETUP` to the flow-gate (alongside `CART_CONFIRMATION`/`CHECK_IN`/etc., near the top of `handle(...)`):
```java
        if (flow == ConversationFlow.SPECIAL_MODE_SETUP) {
            specialModeService.handle(user, incoming);
            return;
        }
```
and a `/masgain` command block next to `/normal`/`/uaonly`:
```java
        if (incoming instanceof TelegramIncomingUpdate.Text masgain && matches(masgain.text(), "/masgain", "")) {
            specialModeService.startMassGainSetup(user);
            return;
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/SpecialModeService.java \
        src/main/java/com/silporestockai/service/MealPlanService.java \
        src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java \
        src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java
git commit -m "Add mass-gain parameter collection flow and /masgain command"
```

---

## Task 10: UA-producer search-query bias

**Files:**
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java`
- Modify: `src/main/java/com/silporestockai/service/ReadyMealCatalogService.java`
- Test: `src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java` (extend), or a focused new test if that file's fixture setup doesn't fit — check its existing `@BeforeEach` before deciding.

**Interfaces:**
- Consumes: `UserProfile.getOnlyUaProducer()` (existing field), `UserProfileRepository.findByUserId` (existing).
- Produces: no public signature change to `resolveProducts`/`findCandidates` — behavior changes internally based on the profile already reachable by `userId`.

- [ ] **Step 1: Write the failing test**

`CartBuildingIntegrationTest` currently has no `UserProfileRepository` dependency and no `UserProfile` row at all — `connectedUser(chatId)` only inserts a `User` and a `SilpoOAuthToken`. Add the repository and a profile row for this one test:

```java
    @Autowired
    private com.silporestockai.repository.UserProfileRepository userProfileRepository;

    @Test
    void biasesSearchTermsTowardUkrainianProducersWhenTheFlagIsSet() {
        UUID userId = connectedUser(9201L);
        userProfileRepository.save(com.silporestockai.entity.UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .onlyUaProducer(true)
                .build());
        scriptCartTools();
        scriptProductTools();

        cartBuildingService.buildCart(userId, List.of(item("молоко", "1", "л")));

        JsonNode search = MCP.callArguments("silpo_find_products_batch").getFirst();
        List<String> searched = new ArrayList<>();
        search.path("products").forEach(term -> searched.add(term.asText()));
        assertThat(searched).anyMatch(term -> term.contains("молоко") && term.contains("українського виробництва"));
    }
```
Add `userProfileRepository.deleteAll();` to the `@BeforeEach clean()` method (before `tokenRepository.deleteAll()`, matching the FK-safe deletion order other test classes use — profile references `users(id)` with `deleteCascade: true` per the `005-user-profile` changeset, so deleting it before `userRepository.deleteAll()` is what matters; order relative to `tokenRepository` does not).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*CartBuildingIntegrationTest*"`
Expected: FAIL — the searched term is the bare item name, no bias suffix.

- [ ] **Step 3: Implement**

In `CartBuildingService.java`: add `private final UserProfileRepository userProfileRepository;` to the constructor fields (import `com.silporestockai.repository.UserProfileRepository`). In `resolveProducts(...)`, before the batching loop, resolve the bias flag once:

```java
        boolean onlyUaProducer = userProfileRepository
                .findByUserId(userId)
                .map(profile -> Boolean.TRUE.equals(profile.getOnlyUaProducer()))
                .orElse(false);
```

Change the `"products"` value builder from:
```java
                            "products",
                                    chunk.stream()
                                            .map(ShoppingListItem::getName)
                                            .toList()));
```
to:
```java
                            "products",
                                    chunk.stream()
                                            .map(item -> biasedSearchTerm(item.getName(), onlyUaProducer))
                                            .toList()));
```

Add the helper (near `nullSafe`):
```java
    /**
     * Best-effort UA-producer preference: no producer/country field exists anywhere in this app's observed MCP
     * product data (never exercised against a live server — see task 25's design doc), so this biases Silpo's own
     * search ranking via the query text instead of filtering results client-side. Not a guaranteed filter.
     */
    static String biasedSearchTerm(String itemName, boolean onlyUaProducer) {
        return onlyUaProducer ? itemName + " українського виробництва" : itemName;
    }
```

**Important:** the matching-back logic right after the call compares the returned query's name to the original item name case-insensitively:
```java
                ShoppingListItem item = queryText == null
                        ? null
                        : chunk.stream()
                                .filter(candidate -> candidate.getName().equalsIgnoreCase(queryText))
                                .findFirst()
                                .orElse(null);
```
Since `queryText` now echoes back the *biased* search string (Silpo's `queries[].query` field mirrors what was sent), this match must compare against the biased term too, or every UA-only search will fail to match back to its `ShoppingListItem`. Change the filter to:
```java
                                .filter(candidate ->
                                        biasedSearchTerm(candidate.getName(), onlyUaProducer).equalsIgnoreCase(queryText))
```

In `ReadyMealCatalogService.java`: add `private final UserProfileRepository userProfileRepository;` (import). In `findCandidates(UUID userId)`, before building the `call(...)` arguments:
```java
        boolean onlyUaProducer = userProfileRepository
                .findByUserId(userId)
                .map(profile -> Boolean.TRUE.equals(profile.getOnlyUaProducer()))
                .orElse(false);
        List<String> searchTerms = onlyUaProducer
                ? CATEGORY_SEARCH_TERMS.stream().map(term -> term + " українського виробництва").toList()
                : CATEGORY_SEARCH_TERMS;
```
and change `"products", CATEGORY_SEARCH_TERMS` to `"products", searchTerms`. (This method keeps every returned product regardless of query text — see its class javadoc — so no matching-back adjustment is needed here, unlike `CartBuildingService`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*CartBuildingIntegrationTest*"`
Expected: PASS, and re-run the full `CartBuildingIntegrationTest` class to confirm the matching-back change didn't break the 18 existing tests (all use `onlyUaProducer=false`, where `biasedSearchTerm` is a no-op passthrough, so behavior is unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/CartBuildingService.java \
        src/main/java/com/silporestockai/service/ReadyMealCatalogService.java \
        src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java
git commit -m "Bias product search queries toward Ukrainian producers when only_ua_producer is set"
```

---

## Task 11: Full-flow demo scenario test and final verification

**Files:**
- Modify: `src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java`

**Interfaces:**
- None new — this task composes everything from Tasks 5–9 into the exact sequence the manual test (and the demo recording) will follow, as an automated regression guard.

- [ ] **Step 1: Write the end-to-end test**

```java
    @Test
    void theFullGastritisDemoScenario() throws Exception {
        UUID baselineBefore = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow()
                .getId();
        CLAUDE.respondWithTexts(
                "{\"isIllnessTrigger\":true,\"confidence\":0.95}", // intent classification
                MealPlanIntegrationTest.fullWeekJson()); // gastritis-acute plan

        sendText(1, "я захворів, гастрит, два тижні дієтичного раціону");

        assertThat(userProfileRepository.findByUserId(user.getId()).orElseThrow().getSpecialMode())
                .isEqualTo(SpecialMode.MEDICAL_GASTRITIS_ACUTE);

        // Fast-forward past the acute stage.
        UserProfile afterTrigger = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        afterTrigger.setSpecialModeStartedAt(Instant.now().minusSeconds(1_000_000));
        afterTrigger.setSpecialModeExpiresAt(Instant.now().minusSeconds(1));
        userProfileRepository.save(afterTrigger);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson()); // diet-table-5 plan

        specialModeService.sweepExpired();

        assertThat(userProfileRepository.findByUserId(user.getId()).orElseThrow().getSpecialMode())
                .isEqualTo(SpecialMode.MEDICAL_DIET_TABLE_5);

        // Fast-forward past diet-table-5 too.
        UserProfile afterStepDown = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        afterStepDown.setSpecialModeExpiresAt(Instant.now().minusSeconds(1));
        userProfileRepository.save(afterStepDown);
        CLAUDE.reset();
        CLAUDE.respondWithText(MealPlanIntegrationTest.fullWeekJson()); // normal plan

        specialModeService.sweepExpired();

        UserProfile normalAgain = userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(normalAgain.getSpecialMode()).isEqualTo(SpecialMode.NONE);
        assertThat(normalAgain.getSpecialModeExpiresAt()).isNull();
        assertThat(baselineBasketRepository
                        .findByUserIdAndIsCurrentTrue(user.getId())
                        .orElseThrow()
                        .getId())
                .as("the household's normal baseline must never change through the whole gastritis cycle")
                .isEqualTo(baselineBefore);
    }
```

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests "*SpecialModeIntegrationTest*"`
Expected: PASS — if it fails, the bug is almost certainly in the ordering of `CLAUDE.respondWithTexts(...)` vs. how many Claude calls each step makes (intent classification is one call, each plan regeneration is one call); adjust the scripted response order to match, don't change the assertions.

- [ ] **Step 3: Run the full suite**

Run: `./gradlew test 2>&1 | tail -60`
Expected: All tests pass except the one pre-existing, unrelated `ArchitectureTest` failure noted in the spec (naming convention on 4 nested classes not touched by this feature) — if that failure is gone too, fine; if any *other* test now fails, fix the regression before proceeding.

Run: `./gradlew spotlessApply` (or `make format`), then `./gradlew spotlessCheck build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/silporestockai/integration/SpecialModeIntegrationTest.java
git commit -m "Add the full gastritis demo-scenario regression test"
```

---

## After the plan: manual verification and wrap-up (not a coding task)

Once Task 11 is green, do the manual test the user asked for, exactly as scoped in the spec's "Manual verification" section:

1. `make run`, complete onboarding for a real/test Telegram chat with a confirmed first order (so a `BaselineBasket` exists).
2. Send "я захворів, гастрит" — confirm the bot switches mode and presents a visibly different (medical) plan/list.
3. Set `GASTRITIS_ACUTE_DURATION=30s` and `GASTRITIS_DIET5_DURATION=30s` (env or `.env`), restart, wait, and either let the cron fire or call the sweep directly to confirm ACUTE→DIET_TABLE_5→NONE, with a Telegram message at each transition.
4. Confirm a `/reorder` right after reversion still reflects the pre-gastritis baseline quantities (proves `BaselineBasket` was untouched).
5. Spot-check `/masgain` (answer the three prompts, confirm a visibly higher-calorie plan) and `/uaonly` (toggle, confirm the flag persists, confirm `silpo_find_products_batch` calls in the logs carry the biased search terms).

Then:
6. Check off the Notion task 25 acceptance-criteria boxes that now hold.
7. Update task 25's Notion status to "Done".
8. Update the "Сценарій демо-запису" doc: flip step 8 (гастрит) to 🟢.
9. `git commit` any leftover manual-test config changes (or revert the shortened durations before committing — don't leave `GASTRITIS_ACUTE_DURATION=30s` as a committed default).
