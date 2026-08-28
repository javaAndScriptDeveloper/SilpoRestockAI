# Core Domain Schema Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The nine remaining MVP tables as Liquibase changesets, with matching JPA entities and Spring Data repositories whose query methods are the ones tasks 06 to 16 actually need.

**Architecture:** Liquibase owns the schema (`ddl-auto: validate`), one changeset file per table, numbered `003` upward because `001` and `002` shipped with tasks 02 and 03. Entities mirror the existing `SilpoOAuthToken` / `ConversationState` shape. Foreign keys are plain `UUID userId` columns rather than `@ManyToOne` associations, because every consumer looks rows up by user id. jsonb columns map through Hibernate's native `@JdbcTypeCode(SqlTypes.JSON)`.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Hibernate 7 native JSON mapping, Liquibase, Spring Data JPA, Testcontainers PostgreSQL, JUnit 5, AssertJ, Instancio.

**Spec:** `docs/superpowers/specs/2026-08-29-core-schema-design.md`

## Global Constraints

- Base package is `com.silporestockai`. Entities in `entity`, repositories in `repository`, enums and JSON payload records in `model`.
- **Liquibase owns the schema.** Changesets live in `src/main/resources/db/changelog/changes/`, named `NNN-....yaml`; the master uses `includeAll`. A missing changeset makes *every* `@SpringBootTest` fail with `Schema validation: missing table`.
- **ArchUnit is enforced.** Classes under `..repository..` must end with `Repository`. Constructor injection only.
- **Spotless (palantir).** Run `make format` before every commit; CI runs `spotlessCheck` before `build`.
- `@Slf4j` for logging, never a manual `LoggerFactory`.
- Timestamps are `Instant` mapped to `TIMESTAMP WITH TIME ZONE`. Dates that are genuinely calendar dates (`week_start_date`) are `LocalDate` / `DATE`.
- Money is `BigDecimal` mapped to `NUMERIC(10,2)`. Never `double`.
- The table is `customer_order`, never `order` — `ORDER` is reserved in PostgreSQL.
- No business logic anywhere in this change. Schema, entities, repositories, fixtures.
- Run tests with `./gradlew test`. Docker must be running.

---

### Task 1: Enums, JSON payload records, `users`, and the retrofitted foreign key

**Files:**
- Create: `src/main/resources/db/changelog/changes/003-users.yaml`
- Create: `src/main/resources/db/changelog/changes/004-mcp-oauth-token-user-fk.yaml`
- Create: `src/main/java/com/silporestockai/model/SpecialMode.java`
- Create: `src/main/java/com/silporestockai/model/OrderType.java`
- Create: `src/main/java/com/silporestockai/model/OrderStatus.java`
- Create: `src/main/java/com/silporestockai/model/TrustTier.java`
- Create: `src/main/java/com/silporestockai/model/BasketItem.java`
- Create: `src/main/java/com/silporestockai/model/CheckinDelta.java`
- Create: `src/main/java/com/silporestockai/entity/User.java`
- Create: `src/main/java/com/silporestockai/repository/UserRepository.java`
- Test: `src/test/java/com/silporestockai/integration/SchemaRoundTripIntegrationTest.java`

**Interfaces:**
- Consumes: `com.silporestockai.integration.AbstractIntegrationTest` (existing: `@SpringBootTest`, `@ActiveProfiles("test")`, Testcontainers PostgreSQL); the existing `SilpoOAuthToken` entity and `SilpoOAuthTokenRepository`.
- Produces:
  - `enum SpecialMode { NONE, MEDICAL_GASTRITIS_ACUTE, MEDICAL_DIET_TABLE_5, MASS_GAIN, BLACKOUT }`
  - `enum OrderType { INITIAL, SCHEDULED_REORDER, AD_HOC }`
  - `enum OrderStatus { DRAFT, CONFIRMED, CANCELLED }`
  - `enum TrustTier { MANUAL_CONFIRM, FAST_CONFIRM }`
  - `record BasketItem(String silpoProductId, String name, String unit, BigDecimal quantity, BigDecimal price)`
  - `record CheckinDelta(List<String> stillHave, List<String> runningLow, List<String> goneCompletely)`
  - `User` with `getId()`, `getTelegramChatId()`, `getSilpoGuestId()`, `getCreatedAt()`, and a Lombok builder
  - `UserRepository extends JpaRepository<User, UUID>` with `findByTelegramChatId(long)` and `findBySilpoGuestId(String)`, both returning `Optional<User>`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/silporestockai/integration/SchemaRoundTripIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.User;
import com.silporestockai.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Round-trips every entity through a real PostgreSQL. {@code ddl-auto: validate} already catches a column
 * that does not exist; only writing and reading a row back catches a jsonb mapping that does not work.
 */
@DisplayName("every entity round-trips through PostgreSQL")
class SchemaRoundTripIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    private User persistedUser(long chatId) {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .telegramChatId(chatId)
                .silpoGuestId("guest-" + chatId)
                .createdAt(Instant.now())
                .build());
    }

    @Test
    void usersRoundTripAndAreFoundByTelegramChatId() {
        User saved = persistedUser(5001L);

        Optional<User> byChat = userRepository.findByTelegramChatId(5001L);
        Optional<User> byGuest = userRepository.findBySilpoGuestId("guest-5001");

        assertThat(byChat).isPresent();
        assertThat(byChat.get().getId()).isEqualTo(saved.getId());
        assertThat(byChat.get().getCreatedAt()).isNotNull();
        assertThat(byGuest).isPresent();
        assertThat(userRepository.findByTelegramChatId(9999L)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SchemaRoundTripIntegrationTest*'`
Expected: FAIL — compilation error, `User` and `UserRepository` do not exist.

- [ ] **Step 3: Write the two changesets**

Create `src/main/resources/db/changelog/changes/003-users.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 003-users
      author: komora
      comment: >-
        One row per person using the bot. telegram_chat_id is the identity we actually receive on every
        webhook call; silpo_guest_id is filled in once the guest connects their Silpo account.
      changes:
        - createTable:
            tableName: users
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_users
                    nullable: false
              - column:
                  name: telegram_chat_id
                  type: BIGINT
                  constraints:
                    nullable: false
                    unique: true
                    uniqueConstraintName: ux_users_telegram_chat_id
              - column:
                  name: silpo_guest_id
                  type: VARCHAR(255)
              - column:
                  name: created_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
```

Create `src/main/resources/db/changelog/changes/004-mcp-oauth-token-user-fk.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 004-mcp-oauth-token-user-fk
      author: komora
      comment: >-
        Task 02 shipped mcp_oauth_token before the users table existed, leaving user_id a plain unique
        column. Now that users exists, make it a real foreign key. Deleting a user drops their tokens.
      changes:
        - addForeignKeyConstraint:
            constraintName: fk_mcp_oauth_token_user
            baseTableName: mcp_oauth_token
            baseColumnNames: user_id
            referencedTableName: users
            referencedColumnNames: id
            onDelete: CASCADE
```

- [ ] **Step 4: Write the enums**

Create `src/main/java/com/silporestockai/model/SpecialMode.java`:

```java
package com.silporestockai.model;

/**
 * A temporary override of the user's normal eating pattern. Persisted by name, so entries may be added but
 * existing names must not be renamed without a migration.
 */
public enum SpecialMode {
    /** Normal profile; no override in effect. */
    NONE,
    /** First, strictest week after the user reports gastritis. */
    MEDICAL_GASTRITIS_ACUTE,
    /** The gentler diet the acute phase steps down into. */
    MEDICAL_DIET_TABLE_5,
    /** High-calorie, high-protein plan for deliberate weight gain. */
    MASS_GAIN,
    /** No cooking and no refrigeration — ready meals and preserves only. */
    BLACKOUT
}
```

Create `src/main/java/com/silporestockai/model/OrderType.java`:

```java
package com.silporestockai.model;

/** Why an order exists. Persisted by name. */
public enum OrderType {
    /** The first basket, built at the end of onboarding. */
    INITIAL,
    /** A scheduled restock built from the delta against the baseline. */
    SCHEDULED_REORDER,
    /** A one-off request outside the normal cycle. */
    AD_HOC
}
```

Create `src/main/java/com/silporestockai/model/OrderStatus.java`:

```java
package com.silporestockai.model;

/** Where an order is in its lifecycle. Persisted by name. */
public enum OrderStatus {
    /** Built but not yet shown to or accepted by the user. */
    DRAFT,
    /** The user pressed confirm. Payment still happens on Silpo's own checkout. */
    CONFIRMED,
    /** The user declined, or the order was abandoned. */
    CANCELLED
}
```

Create `src/main/java/com/silporestockai/model/TrustTier.java`:

```java
package com.silporestockai.model;

/**
 * How much of the confirmation ceremony a user still needs.
 *
 * <p>An auto-confirm tier is deliberately absent: the product brief says to leave room for it, not to build
 * it. Adding it is a product decision, not a schema one.
 */
public enum TrustTier {
    /** Every order is reviewed item by item before confirmation. */
    MANUAL_CONFIRM,
    /** The user has stopped editing suggestions; show a summary and a single confirm. */
    FAST_CONFIRM
}
```

- [ ] **Step 5: Write the JSON payload records**

Create `src/main/java/com/silporestockai/model/BasketItem.java`:

```java
package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One line of a basket, as stored in the {@code items_json} column of {@code baseline_basket} and
 * {@code customer_order}.
 *
 * @param silpoProductId product id from the Silpo MCP catalogue; null for an item that could not be resolved
 * @param name human-readable name, which is what a check-in message will refer to
 * @param unit unit the quantity is counted in, e.g. {@code шт} or {@code кг}
 * @param quantity how much was ordered
 * @param price line price at the time of confirmation; prices move, so the snapshot keeps its own
 */
public record BasketItem(String silpoProductId, String name, String unit, BigDecimal quantity, BigDecimal price) {}
```

Create `src/main/java/com/silporestockai/model/CheckinDelta.java`:

```java
package com.silporestockai.model;

import java.util.List;

/**
 * A check-in reduced to three buckets, as stored in {@code checkin.parsed_delta_json}.
 *
 * <p>Item names come from the user's current baseline: task 12 puts the baseline in the prompt so the model
 * maps loose phrasing onto real items rather than inventing new ones.
 *
 * @param stillHave items the user reports having enough of
 * @param runningLow items about to run out
 * @param goneCompletely items already gone
 */
public record CheckinDelta(List<String> stillHave, List<String> runningLow, List<String> goneCompletely) {}
```

- [ ] **Step 6: Write the entity and repository**

Create `src/main/java/com/silporestockai/entity/User.java`:

```java
package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One person using the bot.
 *
 * <p>{@code telegramChatId} is the identity that actually arrives on every webhook call.
 * {@code silpoGuestId} is filled in once the guest connects their Silpo account and stays null until then.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class User {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "telegram_chat_id", nullable = false, unique = true)
    private Long telegramChatId;

    @Column(name = "silpo_guest_id")
    private String silpoGuestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

Create `src/main/java/com/silporestockai/repository/UserRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Users, looked up by the identity whichever channel is asking already has. */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Task 06 resolves the person behind an incoming Telegram update this way. */
    Optional<User> findByTelegramChatId(long telegramChatId);

    Optional<User> findBySilpoGuestId(String silpoGuestId);
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew test --tests '*SchemaRoundTripIntegrationTest*'`
Expected: PASS, 1 test.

If it fails with `Schema validation: missing table [users]`, confirm both new YAML files appear under
`build/resources/main/db/changelog/changes/` after the build — `includeAll` picks up whatever is on the
classpath, so a file that was not copied is invisible to Liquibase.

- [ ] **Step 8: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/resources/db/changelog/changes/003-users.yaml \
        src/main/resources/db/changelog/changes/004-mcp-oauth-token-user-fk.yaml \
        src/main/java/com/silporestockai/model/ \
        src/main/java/com/silporestockai/entity/User.java \
        src/main/java/com/silporestockai/repository/UserRepository.java \
        src/test/java/com/silporestockai/integration/SchemaRoundTripIntegrationTest.java
git commit -m "Add the users table and give tokens a real owner"
```

---

### Task 2: Profile and meal planning tables

**Files:**
- Create: `src/main/resources/db/changelog/changes/005-user-profile.yaml`
- Create: `src/main/resources/db/changelog/changes/006-meal-plan.yaml`
- Create: `src/main/resources/db/changelog/changes/007-shopping-list-item.yaml`
- Create: `src/main/java/com/silporestockai/entity/UserProfile.java`
- Create: `src/main/java/com/silporestockai/entity/MealPlan.java`
- Create: `src/main/java/com/silporestockai/entity/ShoppingListItem.java`
- Create: `src/main/java/com/silporestockai/repository/UserProfileRepository.java`
- Create: `src/main/java/com/silporestockai/repository/MealPlanRepository.java`
- Create: `src/main/java/com/silporestockai/repository/ShoppingListItemRepository.java`
- Modify: `src/test/java/com/silporestockai/integration/SchemaRoundTripIntegrationTest.java`

**Interfaces:**
- Consumes: `User`, `UserRepository`, `SpecialMode` from Task 1.
- Produces:
  - `UserProfile` with `getUserId()`, `getHouseholdSize()`, `getHasKids()`, `getKidsAges()` → `List<Integer>`, `getDietaryRestrictions()` / `getDislikedFoods()` → `List<String>`, `getWeeklyBudget()` → `BigDecimal`, `getOnlyUaProducer()`, `getSpecialMode()` → `SpecialMode`, `getSpecialModeStartedAt()`
  - `MealPlan` with `getUserId()`, `getWeekStartDate()` → `LocalDate`, `getPlan()` → `Map<String, Object>`, `getCreatedAt()`
  - `ShoppingListItem` with `getMealPlanId()` (nullable), `getName()`, `getQuantity()` → `BigDecimal`, `getUnit()`, `getCategory()`
  - `UserProfileRepository.findByUserId(UUID) -> Optional<UserProfile>`
  - `MealPlanRepository.findByUserIdAndWeekStartDate(UUID, LocalDate) -> Optional<MealPlan>`, `findFirstByUserIdOrderByWeekStartDateDesc(UUID) -> Optional<MealPlan>`
  - `ShoppingListItemRepository.findByMealPlanId(UUID) -> List<ShoppingListItem>`, `deleteByMealPlanId(UUID) -> void`

- [ ] **Step 1: Write the failing tests**

Append to `SchemaRoundTripIntegrationTest`, and add the matching `@Autowired` fields and `deleteAll()` calls:

```java
    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private MealPlanRepository mealPlanRepository;

    @Autowired
    private ShoppingListItemRepository shoppingListItemRepository;

    @Test
    void userProfilesRoundTripIncludingTheirJsonColumns() {
        User user = persistedUser(5002L);

        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(4)
                .hasKids(true)
                .kidsAges(List.of(3, 7))
                .dietaryRestrictions(List.of("без горіхів"))
                .weeklyBudget(new BigDecimal("2500.00"))
                .dislikedFoods(List.of("броколі"))
                .onlyUaProducer(true)
                .specialMode(SpecialMode.MEDICAL_DIET_TABLE_5)
                .specialModeStartedAt(Instant.now())
                .build());

        UserProfile reloaded =
                userProfileRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(reloaded.getHouseholdSize()).isEqualTo(4);
        assertThat(reloaded.getKidsAges()).containsExactly(3, 7);
        assertThat(reloaded.getDietaryRestrictions()).containsExactly("без горіхів");
        assertThat(reloaded.getDislikedFoods()).containsExactly("броколі");
        assertThat(reloaded.getWeeklyBudget()).isEqualByComparingTo("2500.00");
        assertThat(reloaded.getOnlyUaProducer()).isTrue();
        assertThat(reloaded.getSpecialMode()).isEqualTo(SpecialMode.MEDICAL_DIET_TABLE_5);
    }

    @Test
    void mealPlansRoundTripAndTheLatestOneIsFoundFirst() {
        User user = persistedUser(5003L);
        mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(LocalDate.of(2026, 8, 17))
                .plan(Map.of("monday", "борщ"))
                .createdAt(Instant.now())
                .build());
        mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(LocalDate.of(2026, 8, 24))
                .plan(Map.of("monday", "плов"))
                .createdAt(Instant.now())
                .build());

        MealPlan latest = mealPlanRepository
                .findFirstByUserIdOrderByWeekStartDateDesc(user.getId())
                .orElseThrow();

        assertThat(latest.getWeekStartDate()).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(latest.getPlan()).containsEntry("monday", "плов");
        assertThat(mealPlanRepository.findByUserIdAndWeekStartDate(user.getId(), LocalDate.of(2026, 8, 17)))
                .isPresent();
    }

    @Test
    void shoppingListItemsAttachToAPlanAndCanExistWithoutOne() {
        User user = persistedUser(5004L);
        MealPlan plan = mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(LocalDate.of(2026, 8, 31))
                .plan(Map.of())
                .createdAt(Instant.now())
                .build());

        shoppingListItemRepository.save(ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .mealPlanId(plan.getId())
                .name("молоко")
                .quantity(new BigDecimal("2.000"))
                .unit("шт")
                .category("молочні")
                .build());
        // An ad-hoc list belongs to no weekly plan, which is why meal_plan_id is nullable.
        shoppingListItemRepository.save(ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name("попкорн")
                .quantity(new BigDecimal("1.000"))
                .unit("шт")
                .category("снеки")
                .build());

        assertThat(shoppingListItemRepository.findByMealPlanId(plan.getId())).hasSize(1);
        assertThat(shoppingListItemRepository.count()).isEqualTo(2);
    }
```

Add these imports to the test file:

```java
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.SpecialMode;
import com.silporestockai.repository.MealPlanRepository;
import com.silporestockai.repository.ShoppingListItemRepository;
import com.silporestockai.repository.UserProfileRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
```

and extend `clean()` so child tables go first:

```java
    @BeforeEach
    void clean() {
        shoppingListItemRepository.deleteAll();
        mealPlanRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*SchemaRoundTripIntegrationTest*'`
Expected: FAIL — compilation error, `UserProfile`, `MealPlan` and `ShoppingListItem` do not exist.

- [ ] **Step 3: Write the three changesets**

Create `src/main/resources/db/changelog/changes/005-user-profile.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 005-user-profile
      author: komora
      comment: >-
        Everything the agent knows about how a household eats. Collected partly from the Silpo profile over
        MCP and partly from onboarding questions, so most columns are nullable until they are learned.
      changes:
        - createTable:
            tableName: user_profile
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_user_profile
                    nullable: false
              - column:
                  name: user_id
                  type: UUID
                  constraints:
                    nullable: false
                    unique: true
                    uniqueConstraintName: ux_user_profile_user_id
                    foreignKeyName: fk_user_profile_user
                    references: users(id)
                    deleteCascade: true
              - column:
                  name: household_size
                  type: INT
              - column:
                  name: has_kids
                  type: BOOLEAN
              - column:
                  name: kids_ages
                  type: JSONB
              - column:
                  name: dietary_restrictions
                  type: JSONB
              - column:
                  name: weekly_budget
                  type: NUMERIC(10, 2)
              - column:
                  name: disliked_foods
                  type: JSONB
              - column:
                  name: only_ua_producer
                  type: BOOLEAN
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
              - column:
                  name: special_mode
                  type: VARCHAR(64)
              - column:
                  name: special_mode_started_at
                  type: TIMESTAMP WITH TIME ZONE
```

Create `src/main/resources/db/changelog/changes/006-meal-plan.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 006-meal-plan
      author: komora
      comment: >-
        One generated weekly menu. plan_json stays untyped here: task 07 owns that structure and has not
        defined it yet, so typing it now would be invention.
      changes:
        - createTable:
            tableName: meal_plan
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_meal_plan
                    nullable: false
              - column:
                  name: user_id
                  type: UUID
                  constraints:
                    nullable: false
                    foreignKeyName: fk_meal_plan_user
                    references: users(id)
                    deleteCascade: true
              - column:
                  name: week_start_date
                  type: DATE
                  constraints:
                    nullable: false
              - column:
                  name: plan_json
                  type: JSONB
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
        - createIndex:
            indexName: ix_meal_plan_user_week
            tableName: meal_plan
            columns:
              - column:
                  name: user_id
              - column:
                  name: week_start_date
```

Create `src/main/resources/db/changelog/changes/007-shopping-list-item.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 007-shopping-list-item
      author: komora
      comment: >-
        A line of a shopping list. meal_plan_id is nullable on purpose: the same table carries ad-hoc lists
        that belong to no weekly plan, such as a one-off snack order.
      changes:
        - createTable:
            tableName: shopping_list_item
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_shopping_list_item
                    nullable: false
              - column:
                  name: meal_plan_id
                  type: UUID
                  constraints:
                    foreignKeyName: fk_shopping_list_item_meal_plan
                    references: meal_plan(id)
                    deleteCascade: true
              - column:
                  name: name
                  type: VARCHAR(255)
                  constraints:
                    nullable: false
              - column:
                  name: quantity
                  type: NUMERIC(10, 3)
              - column:
                  name: unit
                  type: VARCHAR(32)
              - column:
                  name: category
                  type: VARCHAR(64)
        - createIndex:
            indexName: ix_shopping_list_item_meal_plan
            tableName: shopping_list_item
            columns:
              - column:
                  name: meal_plan_id
```

- [ ] **Step 4: Write the three entities**

Create `src/main/java/com/silporestockai/entity/UserProfile.java`:

```java
package com.silporestockai.entity;

import com.silporestockai.model.SpecialMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * How a household eats.
 *
 * <p>Most columns are nullable because the profile is filled in progressively: some of it arrives from the
 * Silpo profile over MCP during onboarding, the rest only when a conversation happens to reveal it.
 */
@Entity
@Table(name = "user_profile")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class UserProfile {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "household_size")
    private Integer householdSize;

    @Column(name = "has_kids")
    private Boolean hasKids;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "kids_ages")
    private List<Integer> kidsAges;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dietary_restrictions")
    private List<String> dietaryRestrictions;

    @Column(name = "weekly_budget", precision = 10, scale = 2)
    private BigDecimal weeklyBudget;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "disliked_foods")
    private List<String> dislikedFoods;

    @Column(name = "only_ua_producer", nullable = false)
    @Builder.Default
    private Boolean onlyUaProducer = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "special_mode", length = 64)
    private SpecialMode specialMode;

    @Column(name = "special_mode_started_at")
    private Instant specialModeStartedAt;
}
```

Create `src/main/java/com/silporestockai/entity/MealPlan.java`:

```java
package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A generated weekly menu.
 *
 * <p>{@code plan} is an untyped map on purpose: task 07 owns the structure of a weekly plan and has not
 * defined it. Typing it here would be invention, and changing it later would mean a migration.
 */
@Entity
@Table(name = "meal_plan")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class MealPlan {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "week_start_date", nullable = false)
    private LocalDate weekStartDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_json", nullable = false)
    @Builder.Default
    private Map<String, Object> plan = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

Create `src/main/java/com/silporestockai/entity/ShoppingListItem.java`:

```java
package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One line of a shopping list.
 *
 * <p>{@code mealPlanId} is nullable: the same table carries ad-hoc lists that belong to no weekly plan.
 */
@Entity
@Table(name = "shopping_list_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ShoppingListItem {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "meal_plan_id")
    private UUID mealPlanId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "quantity", precision = 10, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit", length = 32)
    private String unit;

    @Column(name = "category", length = 64)
    private String category;
}
```

- [ ] **Step 5: Write the three repositories**

Create `src/main/java/com/silporestockai/repository/UserProfileRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.UserProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One profile per user. */
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    Optional<UserProfile> findByUserId(UUID userId);
}
```

Create `src/main/java/com/silporestockai/repository/MealPlanRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.MealPlan;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Weekly meal plans. */
public interface MealPlanRepository extends JpaRepository<MealPlan, UUID> {

    Optional<MealPlan> findByUserIdAndWeekStartDate(UUID userId, LocalDate weekStartDate);

    /** The most recent plan, which is what regeneration and reorder logic start from. */
    Optional<MealPlan> findFirstByUserIdOrderByWeekStartDateDesc(UUID userId);
}
```

Create `src/main/java/com/silporestockai/repository/ShoppingListItemRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.ShoppingListItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Shopping list lines, either attached to a weekly plan or standing alone. */
public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, UUID> {

    List<ShoppingListItem> findByMealPlanId(UUID mealPlanId);

    /** Regenerating a plan replaces its list wholesale rather than diffing it. */
    void deleteByMealPlanId(UUID mealPlanId);
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests '*SchemaRoundTripIntegrationTest*'`
Expected: PASS, 4 tests.

If `deleteByMealPlanId` fails at context startup with "No property mealPlanId found", check that the entity
field is named exactly `mealPlanId`. Spring Data derives the query from the field name, not the column name.

- [ ] **Step 7: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/resources/db/changelog/changes/005-user-profile.yaml \
        src/main/resources/db/changelog/changes/006-meal-plan.yaml \
        src/main/resources/db/changelog/changes/007-shopping-list-item.yaml \
        src/main/java/com/silporestockai/entity/UserProfile.java \
        src/main/java/com/silporestockai/entity/MealPlan.java \
        src/main/java/com/silporestockai/entity/ShoppingListItem.java \
        src/main/java/com/silporestockai/repository/UserProfileRepository.java \
        src/main/java/com/silporestockai/repository/MealPlanRepository.java \
        src/main/java/com/silporestockai/repository/ShoppingListItemRepository.java \
        src/test/java/com/silporestockai/integration/SchemaRoundTripIntegrationTest.java
git commit -m "Add profile and meal planning tables"
```

---

### Task 3: Baseline, check-ins and inventory trend

**Files:**
- Create: `src/main/resources/db/changelog/changes/008-baseline-basket.yaml`
- Create: `src/main/resources/db/changelog/changes/009-checkin.yaml`
- Create: `src/main/resources/db/changelog/changes/010-inventory-trend.yaml`
- Create: `src/main/java/com/silporestockai/entity/BaselineBasket.java`
- Create: `src/main/java/com/silporestockai/entity/Checkin.java`
- Create: `src/main/java/com/silporestockai/entity/InventoryTrend.java`
- Create: `src/main/java/com/silporestockai/repository/BaselineBasketRepository.java`
- Create: `src/main/java/com/silporestockai/repository/CheckinRepository.java`
- Create: `src/main/java/com/silporestockai/repository/InventoryTrendRepository.java`
- Modify: `src/test/java/com/silporestockai/integration/SchemaRoundTripIntegrationTest.java`
- Test: `src/test/java/com/silporestockai/integration/BaselineBasketConstraintIntegrationTest.java`

**Interfaces:**
- Consumes: `User`, `UserRepository`, `BasketItem`, `CheckinDelta` from Task 1.
- Produces:
  - `BaselineBasket` with `getUserId()`, `getItems()` → `List<BasketItem>`, `getConfirmedAt()`, `getIsCurrent()`
  - `Checkin` with `getUserId()`, `getRawInputText()`, `getParsedDelta()` → `CheckinDelta`, `getReceivedAt()`
  - `InventoryTrend` with `getUserId()`, `getItemName()`, `getConsecutiveUntouchedCycles()` → `int`, `getLastUpdated()`
  - `BaselineBasketRepository.findByUserIdAndIsCurrentTrue(UUID) -> Optional<BaselineBasket>`, `findByUserIdOrderByConfirmedAtDesc(UUID) -> List<BaselineBasket>`
  - `CheckinRepository.findFirstByUserIdOrderByReceivedAtDesc(UUID) -> Optional<Checkin>`, `findTop2ByUserIdOrderByReceivedAtDesc(UUID) -> List<Checkin>`, `findByUserIdOrderByReceivedAtDesc(UUID) -> List<Checkin>`
  - `InventoryTrendRepository.findByUserId(UUID) -> List<InventoryTrend>`, `findByUserIdAndItemName(UUID, String) -> Optional<InventoryTrend>`, `findByUserIdAndConsecutiveUntouchedCyclesGreaterThanEqual(UUID, int) -> List<InventoryTrend>`

- [ ] **Step 1: Write the failing tests**

Append to `SchemaRoundTripIntegrationTest` (add the three `@Autowired` repositories, extend `clean()` to
delete them before `userRepository`, and add the imports for the three entities, the three repositories,
`BasketItem` and `CheckinDelta`):

```java
    @Test
    void baselineBasketsRoundTripTheirTypedItemList() {
        User user = persistedUser(5005L);

        baselineBasketRepository.save(BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .items(List.of(new BasketItem(
                        "silpo-1", "молоко 2.5%", "шт", new BigDecimal("2"), new BigDecimal("41.90"))))
                .confirmedAt(Instant.now())
                .isCurrent(true)
                .build());

        BaselineBasket current = baselineBasketRepository
                .findByUserIdAndIsCurrentTrue(user.getId())
                .orElseThrow();

        assertThat(current.getItems()).hasSize(1);
        assertThat(current.getItems().getFirst().name()).isEqualTo("молоко 2.5%");
        assertThat(current.getItems().getFirst().price()).isEqualByComparingTo("41.90");
    }

    @Test
    void theTwoMostRecentCheckinsComeBackNewestFirst() {
        User user = persistedUser(5006L);
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            checkinRepository.save(Checkin.builder()
                    .id(UUID.randomUUID())
                    .userId(user.getId())
                    .rawInputText("чек-ін " + i)
                    .parsedDelta(new CheckinDelta(List.of("молоко"), List.of(), List.of("хліб")))
                    .receivedAt(now.plusSeconds(i))
                    .build());
        }

        List<Checkin> lastTwo = checkinRepository.findTop2ByUserIdOrderByReceivedAtDesc(user.getId());

        assertThat(lastTwo).hasSize(2);
        assertThat(lastTwo.get(0).getRawInputText()).isEqualTo("чек-ін 2");
        assertThat(lastTwo.get(1).getRawInputText()).isEqualTo("чек-ін 1");
        assertThat(lastTwo.get(0).getParsedDelta().goneCompletely()).containsExactly("хліб");
        assertThat(checkinRepository
                        .findFirstByUserIdOrderByReceivedAtDesc(user.getId())
                        .orElseThrow()
                        .getRawInputText())
                .isEqualTo("чек-ін 2");
    }

    @Test
    void removalCandidatesAreThoseAtOrAboveTheThreshold() {
        User user = persistedUser(5007L);
        saveTrend(user.getId(), "гречка", 3);
        saveTrend(user.getId(), "молоко", 0);
        saveTrend(user.getId(), "квасоля", 5);

        List<InventoryTrend> candidates =
                inventoryTrendRepository.findByUserIdAndConsecutiveUntouchedCyclesGreaterThanEqual(
                        user.getId(), 3);

        assertThat(candidates).extracting(InventoryTrend::getItemName).containsExactlyInAnyOrder("гречка", "квасоля");
        assertThat(inventoryTrendRepository.findByUserIdAndItemName(user.getId(), "молоко"))
                .isPresent();
        assertThat(inventoryTrendRepository.findByUserId(user.getId())).hasSize(3);
    }

    private void saveTrend(UUID userId, String itemName, int cycles) {
        inventoryTrendRepository.save(InventoryTrend.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .itemName(itemName)
                .consecutiveUntouchedCycles(cycles)
                .lastUpdated(Instant.now())
                .build());
    }
```

Create `src/test/java/com/silporestockai/integration/BaselineBasketConstraintIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.silporestockai.entity.BaselineBasket;
import com.silporestockai.entity.User;
import com.silporestockai.repository.BaselineBasketRepository;
import com.silporestockai.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The database, not application code, guarantees one current baseline per user. Task 10's confirm flow can
 * be delivered twice by Telegram, so the invariant has to survive a race rather than a careful caller.
 */
@DisplayName("a user cannot have two current baseline baskets")
class BaselineBasketConstraintIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BaselineBasketRepository baselineBasketRepository;

    private UUID userId;

    @BeforeEach
    void clean() {
        baselineBasketRepository.deleteAll();
        userRepository.deleteAll();
        userId = userRepository
                .save(User.builder()
                        .id(UUID.randomUUID())
                        .telegramChatId(6001L)
                        .createdAt(Instant.now())
                        .build())
                .getId();
    }

    private BaselineBasket basket(boolean current) {
        return BaselineBasket.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .items(List.of())
                .confirmedAt(Instant.now())
                .isCurrent(current)
                .build();
    }

    @Test
    void rejectsASecondCurrentBasket() {
        baselineBasketRepository.saveAndFlush(basket(true));

        assertThatThrownBy(() -> baselineBasketRepository.saveAndFlush(basket(true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsManySupersededBaskets() {
        baselineBasketRepository.saveAndFlush(basket(false));
        baselineBasketRepository.saveAndFlush(basket(false));
        baselineBasketRepository.saveAndFlush(basket(true));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*SchemaRoundTripIntegrationTest*' --tests '*BaselineBasketConstraint*'`
Expected: FAIL — compilation error, `BaselineBasket`, `Checkin` and `InventoryTrend` do not exist.

- [ ] **Step 3: Write the three changesets**

Create `src/main/resources/db/changelog/changes/008-baseline-basket.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 008-baseline-basket
      author: komora
      comment: >-
        A snapshot of a confirmed basket, used as the reference point every later check-in is compared
        against. Superseded snapshots are kept rather than deleted so history survives.
      changes:
        - createTable:
            tableName: baseline_basket
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_baseline_basket
                    nullable: false
              - column:
                  name: user_id
                  type: UUID
                  constraints:
                    nullable: false
                    foreignKeyName: fk_baseline_basket_user
                    references: users(id)
                    deleteCascade: true
              - column:
                  name: items_json
                  type: JSONB
                  constraints:
                    nullable: false
              - column:
                  name: confirmed_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
              - column:
                  name: is_current
                  type: BOOLEAN
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
  - changeSet:
      id: 008-baseline-basket-current-index
      author: komora
      comment: >-
        Exactly one current baseline per user, enforced by the database. Telegram can deliver a confirm
        callback twice, so application-side checking alone would leave a race. Liquibase has no portable
        change for a partial index, hence raw SQL with an explicit rollback.
      changes:
        - sql:
            splitStatements: false
            sql: >-
              CREATE UNIQUE INDEX ux_baseline_basket_current ON baseline_basket (user_id) WHERE is_current
      rollback:
        - sql:
            sql: DROP INDEX ux_baseline_basket_current
```

Create `src/main/resources/db/changelog/changes/009-checkin.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 009-checkin
      author: komora
      comment: >-
        One recorded check-in. The raw input is kept alongside the parsed delta so a bad parse can be
        diagnosed later and so preference learning has the original wording to work from.
      changes:
        - createTable:
            tableName: checkin
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_checkin
                    nullable: false
              - column:
                  name: user_id
                  type: UUID
                  constraints:
                    nullable: false
                    foreignKeyName: fk_checkin_user
                    references: users(id)
                    deleteCascade: true
              - column:
                  name: raw_input_text
                  type: TEXT
              - column:
                  name: parsed_delta_json
                  type: JSONB
              - column:
                  name: received_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
        - createIndex:
            indexName: ix_checkin_user_received
            tableName: checkin
            columns:
              - column:
                  name: user_id
              - column:
                  name: received_at
                  descending: true
```

Create `src/main/resources/db/changelog/changes/010-inventory-trend.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 010-inventory-trend
      author: komora
      comment: >-
        How many check-in cycles in a row an item has gone untouched. Deliberately approximate: this tracks
        a trend, not exact stock. The unique constraint on (user_id, item_name) keeps a retried update from
        quietly creating a duplicate row for the same item.
      changes:
        - createTable:
            tableName: inventory_trend
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_inventory_trend
                    nullable: false
              - column:
                  name: user_id
                  type: UUID
                  constraints:
                    nullable: false
                    foreignKeyName: fk_inventory_trend_user
                    references: users(id)
                    deleteCascade: true
              - column:
                  name: item_name
                  type: VARCHAR(255)
                  constraints:
                    nullable: false
              - column:
                  name: consecutive_untouched_cycles
                  type: INT
                  defaultValueNumeric: 0
                  constraints:
                    nullable: false
              - column:
                  name: last_updated
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
        - addUniqueConstraint:
            constraintName: ux_inventory_trend_user_item
            tableName: inventory_trend
            columnNames: user_id, item_name
```

- [ ] **Step 4: Write the three entities**

Create `src/main/java/com/silporestockai/entity/BaselineBasket.java`:

```java
package com.silporestockai.entity;

import com.silporestockai.model.BasketItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A snapshot of a basket the user confirmed, and the reference point every later check-in is compared
 * against.
 *
 * <p>Superseded snapshots are kept with {@code isCurrent = false} rather than deleted. A partial unique
 * index guarantees at most one current row per user.
 */
@Entity
@Table(name = "baseline_basket")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class BaselineBasket {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", nullable = false)
    @Builder.Default
    private List<BasketItem> items = new ArrayList<>();

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private Boolean isCurrent = false;
}
```

Create `src/main/java/com/silporestockai/entity/Checkin.java`:

```java
package com.silporestockai.entity;

import com.silporestockai.model.CheckinDelta;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recorded check-in.
 *
 * <p>The raw wording is kept next to the parsed delta: a bad parse can then be diagnosed after the fact, and
 * preference learning has the original sentence rather than a lossy summary of it.
 */
@Entity
@Table(name = "checkin")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Checkin {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "raw_input_text", columnDefinition = "text")
    private String rawInputText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_delta_json")
    private CheckinDelta parsedDelta;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
```

Create `src/main/java/com/silporestockai/entity/InventoryTrend.java`:

```java
package com.silporestockai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * How many check-in cycles in a row an item has gone untouched.
 *
 * <p>Deliberately approximate. This tracks a trend so the agent can stop suggesting things nobody eats; it
 * is not a stock-counting system.
 */
@Entity
@Table(name = "inventory_trend")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class InventoryTrend {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "consecutive_untouched_cycles", nullable = false)
    @Builder.Default
    private int consecutiveUntouchedCycles = 0;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;
}
```

- [ ] **Step 5: Write the three repositories**

Create `src/main/java/com/silporestockai/repository/BaselineBasketRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.BaselineBasket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Confirmed basket snapshots. */
public interface BaselineBasketRepository extends JpaRepository<BaselineBasket, UUID> {

    /** At most one row can match: a partial unique index enforces that. */
    Optional<BaselineBasket> findByUserIdAndIsCurrentTrue(UUID userId);

    List<BaselineBasket> findByUserIdOrderByConfirmedAtDesc(UUID userId);
}
```

Create `src/main/java/com/silporestockai/repository/CheckinRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.Checkin;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Recorded check-ins, newest first. */
public interface CheckinRepository extends JpaRepository<Checkin, UUID> {

    Optional<Checkin> findFirstByUserIdOrderByReceivedAtDesc(UUID userId);

    /**
     * The latest check-in and the one before it. Trend tracking needs both: an item counts as untouched only
     * when it was reported as still present in two consecutive cycles.
     */
    List<Checkin> findTop2ByUserIdOrderByReceivedAtDesc(UUID userId);

    List<Checkin> findByUserIdOrderByReceivedAtDesc(UUID userId);
}
```

Create `src/main/java/com/silporestockai/repository/InventoryTrendRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.InventoryTrend;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Per-item consumption trend. */
public interface InventoryTrendRepository extends JpaRepository<InventoryTrend, UUID> {

    List<InventoryTrend> findByUserId(UUID userId);

    Optional<InventoryTrend> findByUserIdAndItemName(UUID userId, String itemName);

    /** Removal candidates. The threshold is the caller's, so it stays configurable rather than baked in. */
    List<InventoryTrend> findByUserIdAndConsecutiveUntouchedCyclesGreaterThanEqual(UUID userId, int threshold);
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests '*SchemaRoundTripIntegrationTest*' --tests '*BaselineBasketConstraint*'`
Expected: PASS, 7 tests in the round-trip class and 2 in the constraint class.

If `rejectsASecondCurrentBasket` passes without throwing, the partial index was not created — check that the
`sql` change in `008` ran, because a silently skipped index would leave the invariant unenforced.

- [ ] **Step 7: Format, run the whole suite, commit**

```bash
make format
./gradlew test
git add src/main/resources/db/changelog/changes/008-baseline-basket.yaml \
        src/main/resources/db/changelog/changes/009-checkin.yaml \
        src/main/resources/db/changelog/changes/010-inventory-trend.yaml \
        src/main/java/com/silporestockai/entity/BaselineBasket.java \
        src/main/java/com/silporestockai/entity/Checkin.java \
        src/main/java/com/silporestockai/entity/InventoryTrend.java \
        src/main/java/com/silporestockai/repository/BaselineBasketRepository.java \
        src/main/java/com/silporestockai/repository/CheckinRepository.java \
        src/main/java/com/silporestockai/repository/InventoryTrendRepository.java \
        src/test/java/com/silporestockai/integration/SchemaRoundTripIntegrationTest.java \
        src/test/java/com/silporestockai/integration/BaselineBasketConstraintIntegrationTest.java
git commit -m "Add baseline, check-in and inventory trend tables"
```

---

### Task 4: Orders, trust level, and the fixture pattern

**Files:**
- Create: `src/main/resources/db/changelog/changes/011-customer-order.yaml`
- Create: `src/main/resources/db/changelog/changes/012-trust-level.yaml`
- Create: `src/main/java/com/silporestockai/entity/CustomerOrder.java`
- Create: `src/main/java/com/silporestockai/entity/TrustLevel.java`
- Create: `src/main/java/com/silporestockai/repository/CustomerOrderRepository.java`
- Create: `src/main/java/com/silporestockai/repository/TrustLevelRepository.java`
- Modify: `src/test/java/com/silporestockai/integration/SchemaRoundTripIntegrationTest.java`
- Test: `src/test/java/com/silporestockai/DomainFixturesTest.java`
- Modify: `CLAUDE.md` (schema note)

**Interfaces:**
- Consumes: `User`, `BasketItem`, `OrderType`, `OrderStatus`, `TrustTier` from Task 1; `Fixtures.create(Class)` from the existing `support/Fixtures`.
- Produces:
  - `CustomerOrder` with `getUserId()`, `getType()` → `OrderType`, `getItems()` → `List<BasketItem>`, `getDeliverySlot()`, `getStatus()` → `OrderStatus`, `getSilpoCartId()`, `getCreatedAt()`, `getConfirmedAt()`
  - `TrustLevel` with `getUserId()`, `getConsecutiveUneditedConfirmations()` → `int`, `getCurrentTrustTier()` → `TrustTier`
  - `CustomerOrderRepository.findByUserIdOrderByCreatedAtDesc(UUID) -> List<CustomerOrder>`, `findByUserIdAndStatus(UUID, OrderStatus) -> List<CustomerOrder>`, `findBySilpoCartId(String) -> Optional<CustomerOrder>`
  - `TrustLevelRepository.findByUserId(UUID) -> Optional<TrustLevel>`

- [ ] **Step 1: Write the failing tests**

Append to `SchemaRoundTripIntegrationTest` (add the two `@Autowired` repositories, extend `clean()`, and add
imports for the two entities, the two repositories, `OrderStatus`, `OrderType` and `TrustTier`):

```java
    @Test
    void ordersRoundTripAndAreFilteredByStatusAndCartId() {
        User user = persistedUser(5008L);
        customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(OrderType.INITIAL)
                .items(List.of(new BasketItem("silpo-9", "хліб", "шт", new BigDecimal("1"), new BigDecimal("24.50"))))
                .deliverySlot("2026-08-30 18:00-20:00")
                .status(OrderStatus.CONFIRMED)
                .silpoCartId("cart-abc")
                .createdAt(Instant.now())
                .confirmedAt(Instant.now())
                .build());
        customerOrderRepository.save(CustomerOrder.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .type(OrderType.AD_HOC)
                .items(List.of())
                .status(OrderStatus.DRAFT)
                .createdAt(Instant.now())
                .build());

        assertThat(customerOrderRepository.findByUserIdOrderByCreatedAtDesc(user.getId()))
                .hasSize(2);
        assertThat(customerOrderRepository.findByUserIdAndStatus(user.getId(), OrderStatus.CONFIRMED))
                .singleElement()
                .satisfies(order -> {
                    assertThat(order.getType()).isEqualTo(OrderType.INITIAL);
                    assertThat(order.getItems().getFirst().name()).isEqualTo("хліб");
                    assertThat(order.getDeliverySlot()).isEqualTo("2026-08-30 18:00-20:00");
                });
        assertThat(customerOrderRepository.findBySilpoCartId("cart-abc")).isPresent();
    }

    @Test
    void trustLevelsRoundTrip() {
        User user = persistedUser(5009L);
        trustLevelRepository.save(TrustLevel.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .consecutiveUneditedConfirmations(2)
                .currentTrustTier(TrustTier.FAST_CONFIRM)
                .build());

        TrustLevel reloaded = trustLevelRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(reloaded.getConsecutiveUneditedConfirmations()).isEqualTo(2);
        assertThat(reloaded.getCurrentTrustTier()).isEqualTo(TrustTier.FAST_CONFIRM);
    }
```

Create `src/test/java/com/silporestockai/DomainFixturesTest.java`:

```java
package com.silporestockai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.support.Fixtures;
import java.util.UUID;
import org.instancio.Instancio;
import org.instancio.junit.InstancioExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The fixture pattern later tasks copy: build a fully populated entity with {@link Fixtures}, then pin only
 * the fields the test actually asserts on. Keeps a test about order status from also having to invent a
 * household size.
 */
@ExtendWith(InstancioExtension.class)
@DisplayName("domain entities can be built as test fixtures")
class DomainFixturesTest {

    @Test
    void buildsAFullyPopulatedUserProfile() {
        UserProfile profile = Fixtures.create(UserProfile.class);

        assertThat(profile.getId()).isNotNull();
        assertThat(profile.getUserId()).isNotNull();
        assertThat(profile.getHouseholdSize()).isNotNull();
        assertThat(profile.getSpecialMode()).isNotNull();
    }

    @Test
    void pinsOnlyTheFieldsUnderTest() {
        UUID userId = UUID.randomUUID();

        CustomerOrder order = Instancio.of(CustomerOrder.class)
                .set(field(CustomerOrder::getUserId), userId)
                .set(field(CustomerOrder::getStatus), OrderStatus.CONFIRMED)
                .create();

        assertThat(order.getUserId()).isEqualTo(userId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getSilpoCartId()).isNotBlank();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*SchemaRoundTripIntegrationTest*' --tests '*DomainFixturesTest*'`
Expected: FAIL — compilation error, `CustomerOrder` and `TrustLevel` do not exist.

- [ ] **Step 3: Write the two changesets**

Create `src/main/resources/db/changelog/changes/011-customer-order.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 011-customer-order
      author: komora
      comment: >-
        An order the agent assembled. Named customer_order because ORDER is reserved in PostgreSQL and a
        quoted identifier would leak into every hand-written query written later. Payment is not modelled:
        the guest completes checkout on Silpo's own page, and there is no MCP payment tool.
      changes:
        - createTable:
            tableName: customer_order
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_customer_order
                    nullable: false
              - column:
                  name: user_id
                  type: UUID
                  constraints:
                    nullable: false
                    foreignKeyName: fk_customer_order_user
                    references: users(id)
                    deleteCascade: true
              - column:
                  name: type
                  type: VARCHAR(32)
                  constraints:
                    nullable: false
              - column:
                  name: items_json
                  type: JSONB
                  constraints:
                    nullable: false
              - column:
                  name: delivery_slot
                  type: VARCHAR(255)
              - column:
                  name: status
                  type: VARCHAR(32)
                  constraints:
                    nullable: false
              - column:
                  name: silpo_cart_id
                  type: VARCHAR(255)
              - column:
                  name: created_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
              - column:
                  name: confirmed_at
                  type: TIMESTAMP WITH TIME ZONE
        - createIndex:
            indexName: ix_customer_order_user_created
            tableName: customer_order
            columns:
              - column:
                  name: user_id
              - column:
                  name: created_at
                  descending: true
```

Create `src/main/resources/db/changelog/changes/012-trust-level.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 012-trust-level
      author: komora
      comment: >-
        How much confirmation ceremony a user still needs, one row per user. The tier enum deliberately has
        no auto-confirm value: the product brief says leave room for it, not build it.
      changes:
        - createTable:
            tableName: trust_level
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    primaryKeyName: pk_trust_level
                    nullable: false
              - column:
                  name: user_id
                  type: UUID
                  constraints:
                    nullable: false
                    unique: true
                    uniqueConstraintName: ux_trust_level_user_id
                    foreignKeyName: fk_trust_level_user
                    references: users(id)
                    deleteCascade: true
              - column:
                  name: consecutive_unedited_confirmations
                  type: INT
                  defaultValueNumeric: 0
                  constraints:
                    nullable: false
              - column:
                  name: current_trust_tier
                  type: VARCHAR(32)
                  constraints:
                    nullable: false
```

- [ ] **Step 4: Write the two entities**

Create `src/main/java/com/silporestockai/entity/CustomerOrder.java`:

```java
package com.silporestockai.entity;

import com.silporestockai.model.BasketItem;
import com.silporestockai.model.OrderStatus;
import com.silporestockai.model.OrderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An order the agent assembled.
 *
 * <p>Named {@code customer_order} in the database because {@code ORDER} is reserved in PostgreSQL. Payment
 * is not modelled: the guest completes checkout on Silpo's own page and there is no MCP payment tool.
 */
@Entity
@Table(name = "customer_order")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class CustomerOrder {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private OrderType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items_json", nullable = false)
    @Builder.Default
    private List<BasketItem> items = new ArrayList<>();

    @Column(name = "delivery_slot")
    private String deliverySlot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "silpo_cart_id")
    private String silpoCartId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;
}
```

Create `src/main/java/com/silporestockai/entity/TrustLevel.java`:

```java
package com.silporestockai.entity;

import com.silporestockai.model.TrustTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** How much confirmation ceremony a user still needs. One row per user. */
@Entity
@Table(name = "trust_level")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class TrustLevel {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "consecutive_unedited_confirmations", nullable = false)
    @Builder.Default
    private int consecutiveUneditedConfirmations = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_trust_tier", nullable = false, length = 32)
    @Builder.Default
    private TrustTier currentTrustTier = TrustTier.MANUAL_CONFIRM;
}
```

- [ ] **Step 5: Write the two repositories**

Create `src/main/java/com/silporestockai/repository/CustomerOrderRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.CustomerOrder;
import com.silporestockai.model.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Orders the agent assembled, newest first. */
public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

    List<CustomerOrder> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<CustomerOrder> findByUserIdAndStatus(UUID userId, OrderStatus status);

    /** Resolves the order behind a Silpo cart, which is how a duplicate confirm callback is recognised. */
    Optional<CustomerOrder> findBySilpoCartId(String silpoCartId);
}
```

Create `src/main/java/com/silporestockai/repository/TrustLevelRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.TrustLevel;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One trust record per user. */
public interface TrustLevelRepository extends JpaRepository<TrustLevel, UUID> {

    Optional<TrustLevel> findByUserId(UUID userId);
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests '*SchemaRoundTripIntegrationTest*' --tests '*DomainFixturesTest*'`
Expected: PASS, 9 tests in the round-trip class and 2 fixture tests.

If Instancio cannot build `CustomerOrder`, the usual cause is that it needs a no-argument constructor and
setters — both are present via Lombok, so check that `@NoArgsConstructor` and `@Setter` were not dropped.

- [ ] **Step 7: Note the schema in `CLAUDE.md`**

In `CLAUDE.md`, add this to the "Invariants that break the build" list, after the Liquibase bullet:

```markdown
- **The orders table is `customer_order`, never `order`** — `ORDER` is reserved in PostgreSQL. The entity is
  `CustomerOrder`. Task 05's draft used `order`; renaming it was cheaper than a quoted identifier in every
  future query.
```

- [ ] **Step 8: Verify everything**

```bash
make format
./gradlew test
./gradlew build
```
Expected: `BUILD SUCCESSFUL` for all three.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/changelog/changes/011-customer-order.yaml \
        src/main/resources/db/changelog/changes/012-trust-level.yaml \
        src/main/java/com/silporestockai/entity/CustomerOrder.java \
        src/main/java/com/silporestockai/entity/TrustLevel.java \
        src/main/java/com/silporestockai/repository/CustomerOrderRepository.java \
        src/main/java/com/silporestockai/repository/TrustLevelRepository.java \
        src/test/java/com/silporestockai/integration/SchemaRoundTripIntegrationTest.java \
        src/test/java/com/silporestockai/DomainFixturesTest.java \
        CLAUDE.md
git commit -m "Add order and trust tables with the fixture pattern"
```

---

## Acceptance criteria mapping

| Notion criterion | Proven by |
|---|---|
| All changesets apply cleanly on a fresh database | Every `@SpringBootTest` in the suite; `ddl-auto: validate` fails the whole suite otherwise |
| Each entity has a repository with the query methods later issues need | Tasks 1–4; methods derived from tasks 06, 07, 08, 10, 12, 13, 14, 15, 16 |
| ArchUnit layering passes with the new packages populated | `ArchitectureTest` in the full suite run at the end of each task |
| `InstancioExampleTest`-style fixture for `user_profile` and `order` | Task 4 `DomainFixturesTest` |

Deviations from the task draft, to state in the final commit or PR: `order` renamed to `customer_order`;
`inventory_trend` gained a unique constraint on `(user_id, item_name)` that the draft omits; `baseline_basket`
gained a partial unique index enforcing one current row per user; `conversation_state` deliberately kept
without a foreign key to `users`.
