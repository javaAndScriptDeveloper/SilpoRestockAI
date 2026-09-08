# Own-brand featuring and share-of-category metrics — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split partner placements into paid-external and Silpo-own-brand, and replace the raw
impression counts of task 46 with share-of-category, an honestly-labelled organic baseline, and lift.

**Architecture:** One new column on `partner_promotion` (`promotion_type`, defaulting existing rows to
`PAID_PARTNER`) and one new table `category_resolution_log` holding a row for *every* resolved
shopping-list line, promoted or not — that table is the denominator without which "share" is just the
old count. `CartBuildingService.resolveProducts` writes the log once per cart build, after the second
pass, through a new `CategoryResolutionLogService`. A new `PromotionMetricsService` computes FSR,
baseline and lift and owns the report text; `PartnerPromotionService` goes back to matching and events.

**Tech Stack:** Java 25 / Spring Boot 4, JPA + Liquibase (`ddl-auto: validate`), Lombok, JUnit 5 +
AssertJ, Testcontainers PostgreSQL, Spotless (palantir), ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-08-own-brand-featuring-design.md`

## Global Constraints

- Liquibase owns the schema: every new entity or column needs a changeset under
  `src/main/resources/db/changelog/changes/`, numbered `029-...yaml`, `030-...yaml` (028 is the highest
  in the repo today). Without one, *every* `@SpringBootTest` fails on `validate`.
- Constructor injection only (`@RequiredArgsConstructor`, final fields); no `@Autowired` fields.
  ArchUnit enforces this, plus the `Service` / `Repository` / `Controller` name suffixes.
- `service` may be reached only from `controller` and `job`; service-to-service calls inside the layer
  are fine (`CartBuildingService` already calls `PartnerPromotionService`).
- `@Slf4j` for logging, never a manual `LoggerFactory`.
- Run `make format` (Spotless, palantir) before every commit; CI runs `spotlessCheck` before `build`.
- Tests: `make test`. Docker must be running. If `make run` is up, stop it first — bootRun and the test
  task share `build/classes` and the "flake" that follows is that, not the tests.
- Ukrainian is the language of every user- and operator-facing string, including report headings.
- Never print a lift number without the baseline method that produced it.

---

### Task 1: `promotion_type` on a placement

**Files:**
- Create: `src/main/resources/db/changelog/changes/029-promotion-type.yaml`
- Create: `src/main/java/com/silporestockai/model/PromotionType.java`
- Modify: `src/main/java/com/silporestockai/entity/PartnerPromotion.java`
- Modify: `src/main/java/com/silporestockai/dto/request/PartnerPromotionRequest.java`
- Modify: `src/main/java/com/silporestockai/dto/response/PartnerPromotionResponse.java`
- Modify: `src/main/java/com/silporestockai/service/PartnerPromotionAdminService.java:96` (the builder in `create`)
- Test: `src/test/java/com/silporestockai/integration/PartnerPromotionIntegrationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.silporestockai.model.PromotionType` with constants `PAID_PARTNER`,
  `OWN_BRAND_MARGIN_BOOST`; `PartnerPromotion.getPromotionType()` / `.promotionType(...)` on the
  builder, defaulting to `PAID_PARTNER`; `PartnerPromotionRequest.promotionType()` (nullable).

- [ ] **Step 1: Write the failing test**

Add to `PartnerPromotionIntegrationTest` (and `import org.springframework.jdbc.core.JdbcTemplate;`
plus an `@Autowired private JdbcTemplate jdbcTemplate;` field):

```java
@Test
@DisplayName("a placement created before task 63 reads back as a paid partner placement")
void aRowWithoutAPromotionTypeDefaultsToPaidPartner() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
            """
            insert into partner_promotion
                (id, partner_name, category_or_query, silpo_product_id, product_name,
                 priority_weight, status, created_at)
            values (?, ?, ?, ?, ?, ?, ?, now())
            """,
            id,
            "Яготинське",
            "молоко",
            PARTNER_MILK_ID,
            PARTNER_MILK_NAME,
            100,
            PartnerPromotionStatus.ACTIVE.name());

    PartnerPromotion stored = promotionRepository.findById(id).orElseThrow();

    assertThat(stored.getPromotionType()).isEqualTo(PromotionType.PAID_PARTNER);
}

@Test
@DisplayName("an own-brand placement can be created and keeps its type")
void anOwnBrandPlacementKeepsItsType() {
    PartnerPromotion stored = promotionRepository.save(PartnerPromotion.builder()
            .id(UUID.randomUUID())
            .partnerName("Сільпо власна марка")
            .categoryOrQuery("чай")
            .silpoProductId("p-tea-own")
            .productName("Чай «Премія» чорний")
            .priorityWeight(100)
            .promotionType(PromotionType.OWN_BRAND_MARGIN_BOOST)
            .status(PartnerPromotionStatus.ACTIVE)
            .createdAt(Instant.now())
            .build());

    assertThat(promotionRepository.findById(stored.getId()).orElseThrow().getPromotionType())
            .isEqualTo(PromotionType.OWN_BRAND_MARGIN_BOOST);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*PartnerPromotionIntegrationTest*'`
Expected: FAIL — `PromotionType` does not exist (compilation error).

- [ ] **Step 3: Write the changeset**

`src/main/resources/db/changelog/changes/029-promotion-type.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 029-promotion-type
      author: komora
      comment: >-
        Task 63: a placement is either an external partner paying for preference (PAID_PARTNER — every
        row task 46 created) or Silpo preferring its own high-margin brand through the identical
        mechanism with no payer at all (OWN_BRAND_MARGIN_BOOST). The column default migrates the
        existing rows honestly: they really are paid placements, so nothing has to be guessed.
      changes:
        - addColumn:
            tableName: partner_promotion
            columns:
              - column:
                  name: promotion_type
                  type: VARCHAR(32)
                  defaultValue: PAID_PARTNER
                  constraints:
                    nullable: false
```

- [ ] **Step 4: Write the enum**

`src/main/java/com/silporestockai/model/PromotionType.java`:

```java
package com.silporestockai.model;

/**
 * Why a placement exists (task 63). The resolution mechanism is identical for both; the reporting is not —
 * external revenue and internal margin are two different pools and are never added together.
 */
public enum PromotionType {
    /** An outside brand pays Silpo to be the preferred answer for a category. */
    PAID_PARTNER,
    /** Silpo prefers its own private-label or high-margin brand, with no external payer. */
    OWN_BRAND_MARGIN_BOOST
}
```

- [ ] **Step 5: Add the field to the entity**

In `PartnerPromotion`, after `priorityWeight`, adding `import com.silporestockai.model.PromotionType;`:

```java
    /** Paid external placement, or Silpo's own margin lever — the report never blends the two (task 63). */
    @Enumerated(EnumType.STRING)
    @Column(name = "promotion_type", nullable = false, length = 32)
    @Builder.Default
    private PromotionType promotionType = PromotionType.PAID_PARTNER;
```

- [ ] **Step 6: Carry the type through the admin endpoint**

In `PartnerPromotionRequest`, add a final component and its javadoc line:

```java
 * @param promotionType {@code PAID_PARTNER} (the default when absent) or {@code OWN_BRAND_MARGIN_BOOST}
```

```java
public record PartnerPromotionRequest(
        String partnerName,
        String categoryOrQuery,
        String productQuery,
        Integer priorityWeight,
        Instant activeFrom,
        Instant activeTo,
        UUID verifyAsUserId,
        PromotionType promotionType) {}
```

In `PartnerPromotionAdminService.create`, in the builder, right after `.priorityWeight(...)`:

```java
                .promotionType(request.promotionType() == null ? PromotionType.PAID_PARTNER : request.promotionType())
```

and widen the closing log line to name it:

```java
        log.info(
                "{} placement {} created: «{}» → {} ({}) for {}",
                promotion.getPromotionType(),
                promotion.getId(),
                promotion.getCategoryOrQuery(),
                productName,
                productId,
                promotion.getPartnerName());
```

In `PartnerPromotionResponse`, add `PromotionType promotionType` as the last component and pass
`promotion.getPromotionType()` last in `of(...)`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests '*PartnerPromotionIntegrationTest*'`
Expected: PASS, including the six pre-existing task-46 tests.

- [ ] **Step 8: Format and commit**

```bash
make format
git add src/main/resources/db/changelog/changes/029-promotion-type.yaml \
        src/main/java/com/silporestockai/model/PromotionType.java \
        src/main/java/com/silporestockai/entity/PartnerPromotion.java \
        src/main/java/com/silporestockai/dto/request/PartnerPromotionRequest.java \
        src/main/java/com/silporestockai/dto/response/PartnerPromotionResponse.java \
        src/main/java/com/silporestockai/service/PartnerPromotionAdminService.java \
        src/test/java/com/silporestockai/integration/PartnerPromotionIntegrationTest.java
git commit -m "Split placements into paid-partner and own-brand

Task 63. The same resolution mechanism serves two different businesses:
an outside brand paying for preference, and Silpo preferring its own
high-margin label for free. The column default migrates task 46's rows
as paid, which is what they are, so no backfill has to guess."
```

---

### Task 2: the resolution log table

**Files:**
- Create: `src/main/resources/db/changelog/changes/030-category-resolution-log.yaml`
- Create: `src/main/java/com/silporestockai/entity/CategoryResolutionLog.java`
- Create: `src/main/java/com/silporestockai/repository/CategoryResolutionLogRepository.java`
- Test: `src/test/java/com/silporestockai/integration/CategoryResolutionLogIntegrationTest.java`

**Interfaces:**
- Consumes: `PartnerPromotion` (Task 1) for the foreign key.
- Produces: `CategoryResolutionLog` with builder fields `id`, `userId`, `lineName`,
  `resolvedProductId`, `resolvedProductName`, `promotionId`, `candidateCount` (`Integer`, nullable),
  `occurredAt`; `CategoryResolutionLogRepository extends JpaRepository<CategoryResolutionLog, UUID>`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/silporestockai/integration/CategoryResolutionLogIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("the category resolution log (task 63)")
class CategoryResolutionLogIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CategoryResolutionLogRepository logRepository;

    @Autowired
    private PartnerPromotionRepository promotionRepository;

    @BeforeEach
    void clean() {
        logRepository.deleteAll();
        promotionRepository.deleteAll();
    }

    @Test
    @DisplayName("an ordinary resolution is stored with no promotion behind it")
    void anOrdinaryResolutionRoundTrips() {
        CategoryResolutionLog saved = logRepository.save(CategoryResolutionLog.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .lineName("Молоко")
                .resolvedProductId("p-milk-generic")
                .resolvedProductName("Молоко Селянське 900г")
                .promotionId(null)
                .candidateCount(4)
                .occurredAt(Instant.now())
                .build());

        CategoryResolutionLog read = logRepository.findById(saved.getId()).orElseThrow();
        assertThat(read.getPromotionId()).isNull();
        assertThat(read.getCandidateCount()).isEqualTo(4);
        assertThat(read.getLineName()).isEqualTo("Молоко");
    }

    @Test
    @DisplayName("deleting a placement keeps its resolutions and only forgets who won them")
    void deletingAPlacementLeavesTheDenominatorIntact() {
        PartnerPromotion promotion = promotionRepository.save(PartnerPromotion.builder()
                .id(UUID.randomUUID())
                .partnerName("Яготинське")
                .categoryOrQuery("молоко")
                .silpoProductId("p-milk-partner")
                .productName("Молоко Яготинське 2.5% 900г")
                .priorityWeight(100)
                .status(PartnerPromotionStatus.ACTIVE)
                .createdAt(Instant.now())
                .build());
        UUID logId = logRepository
                .save(CategoryResolutionLog.builder()
                        .id(UUID.randomUUID())
                        .userId(UUID.randomUUID())
                        .lineName("Молоко")
                        .resolvedProductId("p-milk-partner")
                        .resolvedProductName("Молоко Яготинське 2.5% 900г")
                        .promotionId(promotion.getId())
                        .candidateCount(4)
                        .occurredAt(Instant.now())
                        .build())
                .getId();

        promotionRepository.deleteById(promotion.getId());

        CategoryResolutionLog read = logRepository.findById(logId).orElseThrow();
        assertThat(read.getPromotionId()).isNull();
        assertThat(read.getResolvedProductId()).isEqualTo("p-milk-partner");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*CategoryResolutionLogIntegrationTest*'`
Expected: FAIL — `CategoryResolutionLog` does not exist (compilation error).

- [ ] **Step 3: Write the changeset**

`src/main/resources/db/changelog/changes/030-category-resolution-log.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 030-category-resolution-log
      author: komora
      comment: >-
        Task 63: one row per resolved shopping-list line, promoted or not. Featured Share Rate needs a
        denominator — how many times a category was resolved at all — and the task-46 event tables only
        ever see the lines a placement won, so without this table «share» can only ever be the raw count
        again. The promotion foreign key is ON DELETE SET NULL rather than the cascade used for events:
        an event describes a campaign and dies with it, while a resolution is a historical fact about
        what a household actually got, and deleting one campaign must never shrink another brand's
        denominator.
      changes:
        - createTable:
            tableName: category_resolution_log
            columns:
              - column: { name: id, type: UUID, constraints: { primaryKey: true, nullable: false } }
              - column: { name: user_id, type: UUID, constraints: { nullable: false } }
              - column: { name: line_name, type: VARCHAR(256), constraints: { nullable: false } }
              - column: { name: resolved_product_id, type: VARCHAR(64), constraints: { nullable: false } }
              - column: { name: resolved_product_name, type: VARCHAR(256) }
              - column: { name: promotion_id, type: UUID }
              - column: { name: candidate_count, type: INTEGER }
              - column: { name: occurred_at, type: TIMESTAMP WITH TIME ZONE, constraints: { nullable: false } }
        - addForeignKeyConstraint:
            constraintName: fk_category_resolution_log_promotion
            baseTableName: category_resolution_log
            baseColumnNames: promotion_id
            referencedTableName: partner_promotion
            referencedColumnNames: id
            onDelete: SET NULL
        - createIndex:
            indexName: ix_category_resolution_log_promotion
            tableName: category_resolution_log
            columns:
              - column:
                  name: promotion_id
        - createIndex:
            indexName: ix_category_resolution_log_occurred_at
            tableName: category_resolution_log
            columns:
              - column:
                  name: occurred_at
```

- [ ] **Step 4: Write the entity and repository**

`src/main/java/com/silporestockai/entity/CategoryResolutionLog.java`:

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

/**
 * One resolved shopping-list line — the denominator of Featured Share Rate (task 63).
 *
 * <p>Every line a flow resolves lands here, promoted or not. Task 46's event tables only see the lines a
 * placement won, so on their own they can say «featured four times» and never «four of how many».
 *
 * <p>Attribution is by {@code resolvedProductId}, which is exact. There is deliberately no brand column:
 * a brand parsed out of «Молоко «Яготинське» 2,6% п/е» would be a guess, and a guess in the numerator
 * corrupts every share computed from it.
 */
@Entity
@Table(name = "category_resolution_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryResolutionLog {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The line as the household asked it, e.g. «Молоко» — matched against a promotion's category word. */
    @Column(name = "line_name", nullable = false, length = 256)
    private String lineName;

    @Column(name = "resolved_product_id", nullable = false, length = 64)
    private String resolvedProductId;

    @Column(name = "resolved_product_name", length = 256)
    private String resolvedProductName;

    /** The placement that answered this line, or null for an ordinary match. */
    @Column(name = "promotion_id")
    private UUID promotionId;

    /** How many plausible candidates the catalog returned for the line; null when the second pass rescued it. */
    @Column(name = "candidate_count")
    private Integer candidateCount;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
```

`src/main/java/com/silporestockai/repository/CategoryResolutionLogRepository.java`:

```java
package com.silporestockai.repository;

import com.silporestockai.entity.CategoryResolutionLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Resolutions are read whole and grouped in memory (task 63): a category is decided by the same word matcher the
 * cart uses, which is a regex with word boundaries, not something SQL should be asked to re-implement and get
 * subtly different. At a hackathon's volume — roughly twenty rows per cart build — this is the honest trade.
 */
public interface CategoryResolutionLogRepository extends JpaRepository<CategoryResolutionLog, UUID> {}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*CategoryResolutionLogIntegrationTest*'`
Expected: PASS — both tests.

- [ ] **Step 6: Run the schema round-trip test**

Run: `./gradlew test --tests '*SchemaRoundTripIntegrationTest*'`
Expected: PASS — `ddl-auto: validate` agrees the entity matches the changesets.

- [ ] **Step 7: Format and commit**

```bash
make format
git add src/main/resources/db/changelog/changes/030-category-resolution-log.yaml \
        src/main/java/com/silporestockai/entity/CategoryResolutionLog.java \
        src/main/java/com/silporestockai/repository/CategoryResolutionLogRepository.java \
        src/test/java/com/silporestockai/integration/CategoryResolutionLogIntegrationTest.java
git commit -m "Add the category resolution log

Task 63. Share of category needs a denominator, and task 46's event
tables only ever see the lines a placement won. This table records every
resolved line, promoted or not.

The promotion key is ON DELETE SET NULL rather than a cascade: a
resolution is a fact about what a household got, so deleting a campaign
must not quietly shrink every other brand's denominator."
```

---

### Task 3: one definition of "belongs to this category"

**Files:**
- Create: `src/main/java/com/silporestockai/utils/CategoryWords.java`
- Modify: `src/main/java/com/silporestockai/service/PartnerPromotionService.java:106-127` (`match`),
  `:130-152` (`conflicts`), and delete the private `containsWord` / `normalise` at `:260-271`
- Test: `src/test/java/com/silporestockai/unit/CategoryWordsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `CategoryWords.matches(String lineName, String categoryOrQuery) -> boolean` and
  `CategoryWords.normalise(String value) -> String` (trimmed, lower-cased, null-safe).

- [ ] **Step 1: Write the failing test**

`src/test/java/com/silporestockai/unit/CategoryWordsTest.java` (check the package of the existing
unit tests first — if the repo has no `unit` package, put the file next to the other plain JUnit tests
and use their package):

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.silporestockai.utils.CategoryWords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("category word matching (task 63)")
class CategoryWordsTest {

    @Test
    void matchesAWholeWordWhateverTheCase() {
        assertThat(CategoryWords.matches("Молоко", "молоко")).isTrue();
        assertThat(CategoryWords.matches("Молоко 2.5%", "молоко")).isTrue();
        assertThat(CategoryWords.matches("  чай зелений ", "Чай")).isTrue();
    }

    @Test
    void doesNotMatchAWordItIsMerelyInside() {
        assertThat(CategoryWords.matches("Молокопродукти", "молоко")).isFalse();
        assertThat(CategoryWords.matches("Сирники", "сир")).isFalse();
    }

    @Test
    void treatsNullAndBlankAsNoMatch() {
        assertThat(CategoryWords.matches(null, "молоко")).isFalse();
        assertThat(CategoryWords.matches("Молоко", null)).isFalse();
        assertThat(CategoryWords.matches("Молоко", "   ")).isFalse();
    }

    @Test
    void normalisesNullSafely() {
        assertThat(CategoryWords.normalise(null)).isEmpty();
        assertThat(CategoryWords.normalise("  Чай  ")).isEqualTo("чай");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*CategoryWordsTest*'`
Expected: FAIL — `CategoryWords` does not exist (compilation error).

- [ ] **Step 3: Write the utility**

`src/main/java/com/silporestockai/utils/CategoryWords.java`:

```java
package com.silporestockai.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a shopping-list line belongs to a promotion's category — the single definition of that question.
 *
 * <p>Task 46 asks it to decide which line a placement may answer; task 63 asks it again to count how many lines of
 * that category were resolved at all. Two copies of this rule would make every share wrong in a way no test could
 * see, because both halves would still look right on their own.
 *
 * <p>Whole words only: a «молоко» placement must not claim «Молокопродукти».
 */
public final class CategoryWords {

    private CategoryWords() {}

    public static boolean matches(String lineName, String categoryOrQuery) {
        String line = normalise(lineName);
        String word = normalise(categoryOrQuery);
        if (line.isBlank() || word.isBlank()) {
            return false;
        }
        return Pattern.compile("(^|[^\\p{L}])" + Pattern.quote(word) + "(?=$|[^\\p{L}])")
                .matcher(line)
                .find();
    }

    public static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: Point `PartnerPromotionService` at it**

Add `import com.silporestockai.utils.CategoryWords;`. Replace the body of `match` down to the
`containsWord` call:

```java
    public Optional<PartnerPromotion> match(List<PartnerPromotion> candidates, String lineName, UserProfile profile) {
        if (CategoryWords.normalise(lineName).isBlank()) {
            return Optional.empty();
        }
        for (PartnerPromotion promotion : candidates) {
            if (!CategoryWords.matches(lineName, promotion.getCategoryOrQuery())) {
                continue;
            }
```

(the rest of the loop — the `conflicts` check, the log line, the `return` — is unchanged).

In `conflicts`, replace the two `normalise(...)` calls with `CategoryWords.normalise(...)`, including
the ones building `rules`. Then delete the private `containsWord` and private `normalise` methods at
the bottom of the class; keep `percent` for now (Task 6 removes it with `report()`).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests '*CategoryWordsTest*' --tests '*PartnerPromotionIntegrationTest*'`
Expected: PASS — the four new cases and every task-46 case, unchanged behaviour.

- [ ] **Step 6: Format and commit**

```bash
make format
git add src/main/java/com/silporestockai/utils/CategoryWords.java \
        src/main/java/com/silporestockai/service/PartnerPromotionService.java \
        src/test/java/com/silporestockai/unit/CategoryWordsTest.java
git commit -m "Extract the category word matcher

Task 63. The report is about to ask the same question the cart asks —
does this line belong to this promotion's category — and a second copy
of the rule would make every share wrong while both halves still looked
correct in isolation."
```

---

### Task 4: write a row for every resolved line

**Files:**
- Create: `src/main/java/com/silporestockai/service/CategoryResolutionLogService.java`
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java` (field near `:86`,
  candidate bookkeeping near `:885-891`, the write after `secondPass` at `:932`)
- Test: `src/test/java/com/silporestockai/integration/PartnerPromotionIntegrationTest.java`,
  `src/test/java/com/silporestockai/integration/CategoryResolutionLogIntegrationTest.java`

**Interfaces:**
- Consumes: `CategoryResolutionLog`, `CategoryResolutionLogRepository` (Task 2); `ResolvedProduct`
  (existing: `requestedName()`, `productId()`, `catalogName()`, `promotionId()`).
- Produces: `CategoryResolutionLogService.record(UUID userId, List<ResolvedProduct> resolved,
  Map<String, Integer> candidateCounts)` — `candidateCounts` keyed by the lower-cased line name; a
  missing key means an unknown count, stored as null.

- [ ] **Step 1: Write the failing tests**

In `PartnerPromotionIntegrationTest`, add `@Autowired private CategoryResolutionLogRepository logRepository;`,
add `logRepository.deleteAll();` as the **first** line of `clean()` (before `eventRepository.deleteAll()`,
because the log's foreign key points at the promotions those lines delete), and add:

```java
@Test
@DisplayName("every resolved line is logged, promoted or not — that is the FSR denominator")
void everyResolvedLineIsLogged() {
    UUID userId = connectedUser(null);
    PartnerPromotion promotion = milkPromotion();
    catalogHasThePartnerMilk();

    cartBuildingService.buildCart(userId, List.of(item("молоко"), item("гречка")));

    List<CategoryResolutionLog> rows = logRepository.findAll();
    assertThat(rows).hasSize(2);
    assertThat(rows)
            .filteredOn(row -> "молоко".equals(row.getLineName()))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.getPromotionId()).isEqualTo(promotion.getId());
                assertThat(row.getResolvedProductId()).isEqualTo(PARTNER_MILK_ID);
                assertThat(row.getUserId()).isEqualTo(userId);
                assertThat(row.getCandidateCount()).isNotNull();
            });
    assertThat(rows)
            .filteredOn(row -> "гречка".equals(row.getLineName()))
            .singleElement()
            .satisfies(row -> assertThat(row.getPromotionId()).isNull());
}

@Test
@DisplayName("an ordinary match is logged with no placement behind it")
void anUnpromotedResolutionIsStillCounted() {
    UUID userId = connectedUser(null);
    milkPromotion();
    catalogLacksThePartnerMilk();

    cartBuildingService.buildCart(userId, List.of(item("молоко"), item("гречка")));

    assertThat(logRepository.findAll())
            .hasSize(2)
            .allSatisfy(row -> assertThat(row.getPromotionId()).isNull());
}
```

Imports to add there: `com.silporestockai.entity.CategoryResolutionLog`,
`com.silporestockai.repository.CategoryResolutionLogRepository`.

In `CategoryResolutionLogIntegrationTest`, add `@Autowired private CategoryResolutionLogService logService;`
(import `com.silporestockai.service.CategoryResolutionLogService`, `com.silporestockai.model.ResolvedProduct`,
`java.math.BigDecimal`, `java.util.List`, `java.util.Map`) and:

```java
@Test
@DisplayName("a line that cannot be stored costs its row, never the cart")
void aBrokenRowIsSwallowed() {
    UUID userId = UUID.randomUUID();
    ResolvedProduct unstorable = new ResolvedProduct(
            "Молоко", null, "company-3", "branch-7", BigDecimal.ONE, "шт", null, "Молоко Селянське", null, false);

    logService.record(userId, List.of(unstorable), Map.of());

    assertThat(logRepository.findAll()).isEmpty();
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*CategoryResolutionLogIntegrationTest*' --tests '*PartnerPromotionIntegrationTest*'`
Expected: FAIL — `CategoryResolutionLogService` does not exist (compilation error).

- [ ] **Step 3: Write the service**

`src/main/java/com/silporestockai/service/CategoryResolutionLogService.java`:

```java
package com.silporestockai.service;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.model.ResolvedProduct;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.utils.CategoryWords;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Records what every resolved shopping-list line actually became (task 63).
 *
 * <p>The share a partner or an own-brand review is shown needs a denominator, and only the lines nobody promoted
 * can provide one. Written once per cart build, after the second pass, from the final resolution list — so every
 * flow that goes through {@code CartBuildingService} is counted with no flow-specific code.
 *
 * <p>A failure here costs the report its rows and nothing else: the cart it describes has already been built, and
 * evidence must never break the thing it is evidence of. Same rule as {@code PartnerPromotionService.record}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryResolutionLogService {

    private final CategoryResolutionLogRepository repository;
    private final java.time.Clock clock;

    public void record(UUID userId, List<ResolvedProduct> resolved, Map<String, Integer> candidateCounts) {
        if (resolved == null || resolved.isEmpty()) {
            return;
        }
        try {
            Instant now = clock.instant();
            List<CategoryResolutionLog> rows = resolved.stream()
                    .map(product -> CategoryResolutionLog.builder()
                            .id(UUID.randomUUID())
                            .userId(userId)
                            .lineName(product.requestedName())
                            .resolvedProductId(product.productId())
                            .resolvedProductName(product.catalogName())
                            .promotionId(product.promotionId())
                            .candidateCount(candidateCounts == null
                                    ? null
                                    : candidateCounts.get(CategoryWords.normalise(product.requestedName())))
                            .occurredAt(now)
                            .build())
                    .toList();
            repository.saveAll(rows);
            log.debug("logged {} category resolutions for {}", rows.size(), userId);
        } catch (RuntimeException e) {
            log.warn("could not log {} category resolutions for {}: {}", resolved.size(), userId, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Hook it into the cart build**

In `CartBuildingService`, add the import `com.silporestockai.entity.CategoryResolutionLog` is *not*
needed; add the field next to `partnerPromotionService`:

```java
    private final CategoryResolutionLogService categoryResolutionLogService;
```

Before the chunk loop (next to the `promotionFor` map, around line 843), declare the bookkeeping:

```java
        // Task 63: how many plausible candidates the catalog offered for each line, kept for the report's
        // approximated organic baseline. A line the second pass rescues has no first-pass candidate list and
        // stays absent here — an honest missing count rather than a fabricated one.
        Map<String, Integer> candidateCounts = new HashMap<>();
```

Immediately after `candidatesFor` is built inside the chunk loop (after the `List<List<JsonNode>> candidatesFor = ...`
statement, before `productMatchingService.choose(...)`):

```java
            for (int i = 0; i < toMatch.size(); i++) {
                candidateCounts.putIfAbsent(
                        CategoryWords.normalise(toMatch.get(i).getName()),
                        candidatesFor.get(i).size());
            }
```

Add the imports `java.util.HashMap` (if absent) and `com.silporestockai.utils.CategoryWords`.

After the `secondPass(...)` call and before the summary `log.info`, write the rows:

```java
        categoryResolutionLogService.record(userId, resolved, candidateCounts);
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests '*CategoryResolutionLogIntegrationTest*' --tests '*PartnerPromotionIntegrationTest*' --tests '*CartBuildingIntegrationTest*' --tests '*CartSecondPassIntegrationTest*'`
Expected: PASS. The cart tests prove the new write did not disturb resolution.

- [ ] **Step 6: Run the architecture tests**

Run: `./gradlew test --tests '*ArchitectureTest*'`
Expected: PASS — constructor injection, `Service` suffix, layer access.

- [ ] **Step 7: Format and commit**

```bash
make format
git add src/main/java/com/silporestockai/service/CategoryResolutionLogService.java \
        src/main/java/com/silporestockai/service/CartBuildingService.java \
        src/test/java/com/silporestockai/integration/PartnerPromotionIntegrationTest.java \
        src/test/java/com/silporestockai/integration/CategoryResolutionLogIntegrationTest.java
git commit -m "Log every resolved line, not only the promoted ones

Task 63. Written once per cart build from the final resolution list, at
the one point every flow already passes through, so the weekly plan, the
ad-hoc order and the rest are counted without flow-specific code.

A failure logging costs the report its rows and nothing else — the cart
it describes is already built, and evidence must not break the thing it
is evidence of."
```

---

### Task 5: share, baseline and lift

**Files:**
- Create: `src/main/java/com/silporestockai/model/BaselineMethod.java`
- Create: `src/main/java/com/silporestockai/model/PromotionMetrics.java`
- Create: `src/main/java/com/silporestockai/service/PromotionMetricsService.java`
- Test: `src/test/java/com/silporestockai/integration/PromotionMetricsIntegrationTest.java`

**Interfaces:**
- Consumes: `PartnerPromotion` + `PromotionType` (Task 1), `CategoryResolutionLog` +
  `CategoryResolutionLogRepository` (Task 2), `CategoryWords` (Task 3),
  `PartnerPromotionEventRepository.countByPromotionIdAndEventType`.
- Produces: `PromotionMetricsService.metrics() -> List<PromotionMetrics>`;
  `PromotionMetrics(PartnerPromotion promotion, long impressions, long addedToCart, long confirmedOrders,
  long featuredResolutions, long categoryResolutions, Double featuredShareRate, Double baselineShare,
  BaselineMethod baselineMethod)` with `lift()`; `BaselineMethod` constants `MEASURED`,
  `APPROXIMATED`, `UNKNOWN` each with `label()`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/silporestockai/integration/PromotionMetricsIntegrationTest.java`:

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.model.BaselineMethod;
import com.silporestockai.model.PartnerPromotionStatus;
import com.silporestockai.model.PromotionMetrics;
import com.silporestockai.model.PromotionType;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.service.PromotionMetricsService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("featured share, baseline and lift (task 63)")
class PromotionMetricsIntegrationTest extends AbstractIntegrationTest {

    private static final String OWN_TEA_ID = "p-tea-own";

    @Autowired
    private PromotionMetricsService metricsService;

    @Autowired
    private PartnerPromotionRepository promotionRepository;

    @Autowired
    private PartnerPromotionEventRepository eventRepository;

    @Autowired
    private CategoryResolutionLogRepository logRepository;

    @BeforeEach
    void clean() {
        logRepository.deleteAll();
        eventRepository.deleteAll();
        promotionRepository.deleteAll();
    }

    private PartnerPromotion teaPromotion(PromotionType type) {
        return promotionRepository.save(PartnerPromotion.builder()
                .id(UUID.randomUUID())
                .partnerName(type == PromotionType.OWN_BRAND_MARGIN_BOOST ? "Сільпо власна марка" : "Ліптон")
                .categoryOrQuery("чай")
                .silpoProductId(OWN_TEA_ID)
                .productName("Чай «Премія» чорний 100г")
                .priorityWeight(100)
                .promotionType(type)
                .status(PartnerPromotionStatus.ACTIVE)
                .createdAt(Instant.now())
                .build());
    }

    private void resolution(String line, String productId, UUID promotionId, Integer candidates) {
        logRepository.save(CategoryResolutionLog.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .lineName(line)
                .resolvedProductId(productId)
                .resolvedProductName(productId)
                .promotionId(promotionId)
                .candidateCount(candidates)
                .occurredAt(Instant.now())
                .build());
    }

    @Test
    @DisplayName("four featured tea lines out of five resolutions is a share of 80 %")
    void featuredShareRateIsCountedAgainstEveryResolutionInTheCategory() {
        PartnerPromotion tea = teaPromotion(PromotionType.OWN_BRAND_MARGIN_BOOST);
        resolution("чай", OWN_TEA_ID, tea.getId(), 3);
        resolution("чай зелений", OWN_TEA_ID, tea.getId(), 3);
        resolution("чай", OWN_TEA_ID, tea.getId(), 3);
        resolution("чай", OWN_TEA_ID, tea.getId(), 3);
        resolution("чай", "p-tea-other", null, 3);
        // A different category must not touch the tea denominator.
        resolution("молоко", "p-milk-generic", null, 4);

        PromotionMetrics metrics = metricsService.metrics().getFirst();

        assertThat(metrics.categoryResolutions()).isEqualTo(5);
        assertThat(metrics.featuredResolutions()).isEqualTo(4);
        assertThat(metrics.featuredShareRate()).isEqualTo(0.8);
        assertThat(metrics.baselineMethod()).isEqualTo(BaselineMethod.APPROXIMATED);
        assertThat(metrics.baselineShare()).isCloseTo(1.0 / 3, within(0.0001));
        assertThat(metrics.lift()).isCloseTo(0.8 - 1.0 / 3, within(0.0001));
    }

    @Test
    @DisplayName("with enough organic resolutions the baseline is measured, not guessed")
    void aMeasuredBaselineWinsOverTheApproximation() {
        PartnerPromotion tea = teaPromotion(PromotionType.PAID_PARTNER);
        resolution("чай", OWN_TEA_ID, tea.getId(), 4);
        // Six organic resolutions, two of which the ordinary matcher gave the promoted product anyway.
        resolution("чай", OWN_TEA_ID, null, 4);
        resolution("чай", OWN_TEA_ID, null, 4);
        resolution("чай", "p-tea-other", null, 4);
        resolution("чай", "p-tea-other", null, 4);
        resolution("чай", "p-tea-third", null, 4);
        resolution("чай", "p-tea-third", null, 4);

        PromotionMetrics metrics = metricsService.metrics().getFirst();

        assertThat(metrics.baselineMethod()).isEqualTo(BaselineMethod.MEASURED);
        assertThat(metrics.baselineShare()).isCloseTo(2.0 / 6, within(0.0001));
        assertThat(metrics.featuredShareRate()).isCloseTo(1.0 / 7, within(0.0001));
    }

    @Test
    @DisplayName("no candidate counts and too few organic rows means no baseline and no lift")
    void withoutDataTheBaselineIsUnknownAndLiftIsNotInvented() {
        PartnerPromotion tea = teaPromotion(PromotionType.PAID_PARTNER);
        resolution("чай", OWN_TEA_ID, tea.getId(), null);
        resolution("чай", "p-tea-other", null, null);

        PromotionMetrics metrics = metricsService.metrics().getFirst();

        assertThat(metrics.featuredShareRate()).isEqualTo(0.5);
        assertThat(metrics.baselineMethod()).isEqualTo(BaselineMethod.UNKNOWN);
        assertThat(metrics.baselineShare()).isNull();
        assertThat(metrics.lift()).isNull();
    }

    @Test
    @DisplayName("a placement nobody has resolved yet has no share at all")
    void aPlacementWithNoResolutionsHasNoShare() {
        teaPromotion(PromotionType.PAID_PARTNER);

        PromotionMetrics metrics = metricsService.metrics().getFirst();

        assertThat(metrics.categoryResolutions()).isZero();
        assertThat(metrics.featuredShareRate()).isNull();
        assertThat(metrics.lift()).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*PromotionMetricsIntegrationTest*'`
Expected: FAIL — `PromotionMetricsService` does not exist (compilation error).

- [ ] **Step 3: Write the model types**

`src/main/java/com/silporestockai/model/BaselineMethod.java`:

```java
package com.silporestockai.model;

/**
 * How an organic baseline share was arrived at (task 63) — printed beside every baseline and every lift, because a
 * lift is only as good as the baseline under it and the two must never be read apart.
 */
public enum BaselineMethod {
    /** Observed: how often the ordinary matcher picked this product when the placement did not answer. */
    MEASURED("виміряно"),
    /** Guessed from how many candidates the catalog offered — one brand's naive share of them. */
    APPROXIMATED("наближення (1/N кандидатів)"),
    /** Not enough data for either. The baseline and the lift both print «—» rather than a made-up number. */
    UNKNOWN("—");

    private final String label;

    BaselineMethod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
```

`src/main/java/com/silporestockai/model/PromotionMetrics.java`:

```java
package com.silporestockai.model;

import com.silporestockai.entity.PartnerPromotion;

/**
 * What one placement achieved (task 63): the funnel task 46 already counted, plus the share of its category it
 * actually took and how much of that share was the placement's doing.
 *
 * @param featuredResolutions lines in this category the placement answered
 * @param categoryResolutions lines in this category resolved at all — the denominator, including lines the
 *     placement could never have won because the household's restrictions ruled it out
 * @param featuredShareRate featured over category, or null when the category has no resolutions yet
 * @param baselineShare the organic share, or null when {@code baselineMethod} is {@code UNKNOWN}
 */
public record PromotionMetrics(
        PartnerPromotion promotion,
        long impressions,
        long addedToCart,
        long confirmedOrders,
        long featuredResolutions,
        long categoryResolutions,
        Double featuredShareRate,
        Double baselineShare,
        BaselineMethod baselineMethod) {

    /** Share the placement added over the baseline, as a fraction — null unless both numbers are real. */
    public Double lift() {
        return featuredShareRate == null || baselineShare == null ? null : featuredShareRate - baselineShare;
    }
}
```

- [ ] **Step 4: Write the metrics service**

`src/main/java/com/silporestockai/service/PromotionMetricsService.java`:

```java
package com.silporestockai.service;

import com.silporestockai.entity.CategoryResolutionLog;
import com.silporestockai.entity.PartnerPromotion;
import com.silporestockai.model.BaselineMethod;
import com.silporestockai.model.PartnerPromotionEventType;
import com.silporestockai.model.PromotionMetrics;
import com.silporestockai.repository.CategoryResolutionLogRepository;
import com.silporestockai.repository.PartnerPromotionEventRepository;
import com.silporestockai.repository.PartnerPromotionRepository;
import com.silporestockai.utils.CategoryWords;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Share of category, not raw counts (task 63).
 *
 * <p>«Featured four times» says nothing about whether four is a lot. «Answered four of the five tea lines our
 * households asked for» does, and it is the number both a paying partner and Silpo's own margin review actually
 * want. The denominator comes from {@code category_resolution_log}, matched with the same word rule the cart used
 * to decide the placement in the first place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionMetricsService {

    /** Below this many organic resolutions a measured baseline is noise wearing a decimal point. */
    static final int MEASURED_BASELINE_MIN_ROWS = 5;

    private final PartnerPromotionRepository promotionRepository;
    private final PartnerPromotionEventRepository eventRepository;
    private final CategoryResolutionLogRepository resolutionLogRepository;

    public List<PromotionMetrics> metrics() {
        List<CategoryResolutionLog> logs = resolutionLogRepository.findAll();
        return promotionRepository.findAll().stream()
                .sorted(Comparator.comparing(PartnerPromotion::getCreatedAt))
                .map(promotion -> metricsFor(promotion, logs))
                .toList();
    }

    private PromotionMetrics metricsFor(PartnerPromotion promotion, List<CategoryResolutionLog> logs) {
        List<CategoryResolutionLog> category = logs.stream()
                .filter(row -> CategoryWords.matches(row.getLineName(), promotion.getCategoryOrQuery()))
                .toList();
        long featured = category.stream()
                .filter(row -> promotion.getId().equals(row.getPromotionId()))
                .count();
        Double share = category.isEmpty() ? null : (double) featured / category.size();

        List<CategoryResolutionLog> organic = category.stream()
                .filter(row -> row.getPromotionId() == null)
                .toList();
        Double baseline = null;
        BaselineMethod method = BaselineMethod.UNKNOWN;
        if (organic.size() >= MEASURED_BASELINE_MIN_ROWS) {
            // Measured: how often the ordinary matcher reached for this very product with no help.
            long organicHits = organic.stream()
                    .filter(row -> promotion.getSilpoProductId().equals(row.getResolvedProductId()))
                    .count();
            baseline = (double) organicHits / organic.size();
            method = BaselineMethod.MEASURED;
        } else {
            // Approximated: one brand's naive share of the candidates the catalog offered. Rows without a
            // candidate count are left out of the average rather than counted as zero.
            OptionalDouble candidates = category.stream()
                    .filter(row -> row.getCandidateCount() != null && row.getCandidateCount() > 0)
                    .mapToInt(CategoryResolutionLog::getCandidateCount)
                    .average();
            if (candidates.isPresent()) {
                baseline = 1.0 / candidates.getAsDouble();
                method = BaselineMethod.APPROXIMATED;
            }
        }

        return new PromotionMetrics(
                promotion,
                eventRepository.countByPromotionIdAndEventType(promotion.getId(), PartnerPromotionEventType.IMPRESSION),
                eventRepository.countByPromotionIdAndEventType(
                        promotion.getId(), PartnerPromotionEventType.ADDED_TO_CART),
                eventRepository.countByPromotionIdAndEventType(
                        promotion.getId(), PartnerPromotionEventType.CONFIRMED_ORDER),
                featured,
                category.size(),
                share,
                baseline,
                method);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests '*PromotionMetricsIntegrationTest*'`
Expected: PASS — all four cases.

- [ ] **Step 6: Format and commit**

```bash
make format
git add src/main/java/com/silporestockai/model/BaselineMethod.java \
        src/main/java/com/silporestockai/model/PromotionMetrics.java \
        src/main/java/com/silporestockai/service/PromotionMetricsService.java \
        src/test/java/com/silporestockai/integration/PromotionMetricsIntegrationTest.java
git commit -m "Compute featured share, organic baseline and lift

Task 63. Share of category is the number that justifies a price; a raw
impression count is not. The baseline is measured from organic
resolutions when there are enough of them and approximated from
candidate counts otherwise, and it always says which — with no baseline
there is no lift either, and the report prints nothing rather than a
number nobody can stand behind."
```

---

### Task 6: a report split by what the placement is for

**Files:**
- Modify: `src/main/java/com/silporestockai/service/PromotionMetricsService.java` (add `report()`)
- Modify: `src/main/java/com/silporestockai/service/PartnerPromotionService.java` (delete `report()`
  at `:196-239` and the now-unused private `percent` at `:256-258`)
- Modify: `src/main/java/com/silporestockai/controller/InternalPromotionsController.java`
- Test: `src/test/java/com/silporestockai/integration/PromotionMetricsIntegrationTest.java`

**Interfaces:**
- Consumes: `PromotionMetricsService.metrics()` (Task 5), `PromotionType` (Task 1).
- Produces: `PromotionMetricsService.report() -> String` (Markdown); the controller's
  `GET /internal/promotions/report` now serves it.

- [ ] **Step 1: Write the failing test**

Append to `PromotionMetricsIntegrationTest` (imports: `java.util.List`,
`com.silporestockai.model.PartnerPromotionEventType`, `com.silporestockai.entity.PartnerPromotionEvent`):

```java
@Test
@DisplayName("paid placements and own brands are rolled up separately, never blended")
void theTwoValuePoolsAreReportedApart() {
    PartnerPromotion paid = promotionRepository.save(PartnerPromotion.builder()
            .id(UUID.randomUUID())
            .partnerName("Яготинське")
            .categoryOrQuery("молоко")
            .silpoProductId("p-milk-partner")
            .productName("Молоко Яготинське 2.5% 900г")
            .priorityWeight(100)
            .promotionType(PromotionType.PAID_PARTNER)
            .status(PartnerPromotionStatus.ACTIVE)
            .createdAt(Instant.now())
            .build());
    PartnerPromotion own = teaPromotion(PromotionType.OWN_BRAND_MARGIN_BOOST);
    resolution("молоко", "p-milk-partner", paid.getId(), 4);
    resolution("чай", OWN_TEA_ID, own.getId(), 3);
    resolution("чай", "p-tea-other", null, 3);

    String report = metricsService.report();

    int paidHeading = report.indexOf("## Платні розміщення (PAID_PARTNER)");
    int ownHeading = report.indexOf("## Власні марки (OWN_BRAND_MARGIN_BOOST)");
    assertThat(paidHeading).isNotNegative();
    assertThat(ownHeading).isGreaterThan(paidHeading);
    assertThat(report.indexOf("Яготинське")).isBetween(paidHeading, ownHeading);
    assertThat(report.indexOf("Сільпо власна марка")).isGreaterThan(ownHeading);
    // 1 of 2 tea resolutions, and the method is never left off a baseline.
    assertThat(report).contains("50 %").contains("наближення (1/N кандидатів)");
}

@Test
@DisplayName("an unknown baseline prints a dash for the lift too")
void anUnknownBaselineNeverBecomesALiftNumber() {
    PartnerPromotion tea = teaPromotion(PromotionType.PAID_PARTNER);
    resolution("чай", OWN_TEA_ID, tea.getId(), null);

    String report = metricsService.report();

    assertThat(report).contains("| — | — | — |");
}

@Test
@DisplayName("with nothing configured the report says so instead of printing empty tables")
void anEmptyReportIsHonest() {
    assertThat(metricsService.report()).contains("Жодного розміщення ще не налаштовано");
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*PromotionMetricsIntegrationTest*'`
Expected: FAIL — `report()` is not defined on `PromotionMetricsService`.

- [ ] **Step 3: Write the report**

Add to `PromotionMetricsService` (imports: `com.silporestockai.model.PromotionType`,
`java.time.Clock`, `java.util.Locale`, `java.util.Set`, `java.util.stream.Collectors`,
`com.silporestockai.model.PartnerPromotionStatus`), plus the field `private final Clock clock;`:

```java
    /** The operator-facing report behind {@code make promotions}. */
    public String report() {
        List<PromotionMetrics> all = metrics();
        StringBuilder text = new StringBuilder("# Партнерські розміщення — звіт (")
                .append(clock.instant())
                .append(")\n\n");
        if (all.isEmpty()) {
            return text.append("Жодного розміщення ще не налаштовано.\n").toString();
        }
        section(text, "Платні розміщення (PAID_PARTNER)", "Партнер", byType(all, PromotionType.PAID_PARTNER));
        section(
                text,
                "Власні марки (OWN_BRAND_MARGIN_BOOST)",
                "Бренд",
                byType(all, PromotionType.OWN_BRAND_MARGIN_BOOST));
        text.append("> FSR рахується від усіх розв'язань цієї категорії — включно з рядками, де розміщення не мало\n")
                .append("> права виграти (обмеження господарства). Це справжня частка категорії: вона занижує\n")
                .append("> FSR, а не завищує. Базлайн і lift завжди підписані методом розрахунку.\n");
        return text.toString();
    }

    private static List<PromotionMetrics> byType(List<PromotionMetrics> all, PromotionType type) {
        return all.stream()
                .filter(metrics -> metrics.promotion().getPromotionType() == type)
                .toList();
    }

    private static void section(StringBuilder text, String title, String owner, List<PromotionMetrics> rows) {
        text.append("## ").append(title).append("\n\n");
        if (rows.isEmpty()) {
            text.append("Порожньо.\n\n");
            return;
        }
        text.append("| ")
                .append(owner)
                .append(" | Категорія | Товар | Статус | Показів | У кошику | Підтверджено | Кошик→замовлення | ")
                .append("FSR | Базлайн | Метод | Lift |\n");
        text.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (PromotionMetrics metrics : rows) {
            PartnerPromotion promotion = metrics.promotion();
            text.append("| ")
                    .append(promotion.getPartnerName())
                    .append(" | ")
                    .append(promotion.getCategoryOrQuery())
                    .append(" | ")
                    .append(promotion.getProductName())
                    .append(" | ")
                    .append(promotion.getStatus())
                    .append(" | ")
                    .append(metrics.impressions())
                    .append(" | ")
                    .append(metrics.addedToCart())
                    .append(" | ")
                    .append(metrics.confirmedOrders())
                    .append(" | ")
                    .append(percent(metrics.confirmedOrders(), metrics.addedToCart()))
                    .append(" | ")
                    .append(share(metrics.featuredShareRate()))
                    .append(" | ")
                    .append(share(metrics.baselineShare()))
                    .append(" | ")
                    .append(metrics.baselineMethod().label())
                    .append(" | ")
                    .append(points(metrics.lift()))
                    .append(" |\n");
        }
        long added = rows.stream().mapToLong(PromotionMetrics::addedToCart).sum();
        long confirmed = rows.stream().mapToLong(PromotionMetrics::confirmedOrders).sum();
        Set<String> categories = rows.stream()
                .filter(metrics -> metrics.promotion().getStatus() == PartnerPromotionStatus.ACTIVE)
                .map(metrics -> metrics.promotion().getCategoryOrQuery())
                .collect(Collectors.toSet());
        text.append("\nРазом: кошик→замовлення ")
                .append(percent(confirmed, added))
                .append(", активних категорій ")
                .append(categories.size())
                .append(".\n\n");
    }

    private static String percent(long numerator, long denominator) {
        return denominator == 0 ? "—" : String.format(Locale.ROOT, "%.0f %%", 100.0 * numerator / denominator);
    }

    /** A fraction as a percentage, or «—» when we do not have one. Never a zero standing in for «unknown». */
    private static String share(Double fraction) {
        return fraction == null ? "—" : String.format(Locale.ROOT, "%.0f %%", 100.0 * fraction);
    }

    private static String points(Double fraction) {
        return fraction == null ? "—" : String.format(Locale.ROOT, "%+.0f п.п.", 100.0 * fraction);
    }
```

- [ ] **Step 4: Delete the old report and point the controller at the new one**

In `PartnerPromotionService`, delete the whole `report()` method and the private `percent` helper, and
drop the imports left unused (`Locale`, and `Comparator` only if nothing else uses it — `activePromotions`
still does, so keep it). The class keeps `activePromotions`, `match`, `conflicts`, the three `record*`
methods and `onOrderConfirmed`.

In `InternalPromotionsController`, replace the `PartnerPromotionService` field with:

```java
    private final PromotionMetricsService promotionMetricsService;
```

adjust the import, and in `report(...)` return `ResponseEntity.ok(promotionMetricsService.report())`.
Update the class javadoc's second sentence to read: `create a placement, read its share of the
category (task 63)`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests '*PromotionMetricsIntegrationTest*' --tests '*PartnerPromotionIntegrationTest*'`
Expected: PASS. Task 46's report assertion goes through the new text — if it asserted the old
`«1 | 1 | 1 | 100 % | 100 %»` row shape, update that assertion to the new columns (impressions, cart,
confirmed, cart→order) and keep it asserting real counts.

- [ ] **Step 6: Run the whole suite**

Run: `make test`
Expected: PASS, all green.

- [ ] **Step 7: Format and commit**

```bash
make format
git add src/main/java/com/silporestockai/service/PromotionMetricsService.java \
        src/main/java/com/silporestockai/service/PartnerPromotionService.java \
        src/main/java/com/silporestockai/controller/InternalPromotionsController.java \
        src/test/java/com/silporestockai/integration/PromotionMetricsIntegrationTest.java \
        src/test/java/com/silporestockai/integration/PartnerPromotionIntegrationTest.java
git commit -m "Report paid placements and own brands apart

Task 63. External revenue and internal margin are two different pools,
and one blended figure would describe neither. Each section carries its
own funnel, share, baseline, method and lift, and leads with the
cart-to-order rate rather than impressions — that is the stage closest
to money actually changing hands.

PartnerPromotionService goes back to matching and events; the report now
belongs to the service that computes the numbers."
```

---

### Task 7: the operator's checklist

**Files:**
- Modify: `docs/RUNBOOK.md` (new `### Task 63:` section, after the task-47 section)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing code depends on.

- [ ] **Step 1: Write the checklist**

Append after the task-47 section in `docs/RUNBOOK.md`:

```markdown
### Task 63: own-brand featuring and share of category

Needs the app running against the real account (`make run`), `METRICS_TOKEN` in `.env`, and a
connected Silpo session for the user whose id you pass as `verifyAsUserId`.

1. Find a Silpo private label in a category the weekly list already has. Probe broadly first —
   task 46 learned that a composed query («Чай Премія чорний 100г») returns 422 and that a bare
   brand is not deterministic. Search «чай» through the running app's own flow, read the catalog's
   `productName` back, and use that exact string.
2. Create the placement:

   ```bash
   curl -s -X POST "http://localhost:8080/internal/promotions" \
     -H "X-Metrics-Token: $METRICS_TOKEN" -H 'Content-Type: application/json' \
     -d '{"partnerName":"Сільпо власна марка","categoryOrQuery":"чай",
          "productQuery":"<the catalog name, exactly>","promotionType":"OWN_BRAND_MARGIN_BOOST",
          "verifyAsUserId":"<a connected user id>"}'
   ```

   The response echoes the real `silpoProductId` the catalog answered, and `promotionType`.
3. Walk «📝 Список» → «Замовити» → «Підтвердити». The log line to watch for is
   `partner placement <id> answered «Чай» with product <id>`.
4. `make promotions`. Check: the own-brand row sits under «Власні марки», never under «Платні
   розміщення»; FSR's denominator equals the number of tea lines resolved, not the number the
   placement won; the baseline carries «виміряно» or «наближення (1/N кандидатів)» and lift is
   «—» whenever the baseline is.
5. Count by hand once, against the DB, and compare:

   ```sql
   select line_name, resolved_product_id, promotion_id from category_resolution_log order by occurred_at;
   ```

A failed cart build still leaves resolution rows behind, the same way it leaves IMPRESSION events
(task 46) — clear both by `occurred_at` before recording the demo.
```

- [ ] **Step 2: Commit**

```bash
git add docs/RUNBOOK.md
git commit -m "Document the task 63 live check

The report is only trustworthy if someone has counted the denominator by
hand at least once, so the checklist ends with the query that does it."
```

---

## Self-Review

**Spec coverage.** `promotion_type` with a paid default → Task 1. `category_resolution_log` with its
SET NULL key → Task 2. The shared category matcher the spec insists must not drift → Task 3. The write
path in `CartBuildingService` after `secondPass`, with `candidate_count` collected in the first pass
and left null for second-pass lines → Task 4. FSR, the dual baseline with its 5-row threshold, lift
that refuses to exist without a baseline, the confirmed-order rate, category coverage → Tasks 5 and 6.
Split rollups and the denominator footnote → Task 6. The live own-brand seed → Task 7's checklist,
executed by hand after the suite is green (the spec puts it there deliberately: a placement may only
exist with a product the catalog itself answered).

**Placeholders.** None: every step carries the code or command it needs. The two judgement calls are
flagged inline rather than left vague — the unit-test package in Task 3 Step 1, and task 46's existing
report assertion in Task 6 Step 5, which changes shape because the report does.

**Type consistency.** `CategoryWords.matches(lineName, categoryOrQuery)` and `.normalise(value)` are
used with that order and those names in Tasks 3, 4 and 5. `CategoryResolutionLogService.record(userId,
resolved, candidateCounts)` is defined in Task 4 Step 3 and called with those three arguments in Step 4.
`PromotionMetrics` is constructed in Task 5 with exactly the nine components its record declares, and
read in Task 6 through `promotion()`, `impressions()`, `addedToCart()`, `confirmedOrders()`,
`featuredResolutions()`, `categoryResolutions()`, `featuredShareRate()`, `baselineShare()`,
`baselineMethod()`, `lift()`. `PromotionType` values are `PAID_PARTNER` and `OWN_BRAND_MARGIN_BOOST`
throughout. `Clock` is a constructor dependency of `CategoryResolutionLogService` (Task 4) and of
`PromotionMetricsService` (added in Task 6 Step 3, where `report()` first needs a timestamp).
