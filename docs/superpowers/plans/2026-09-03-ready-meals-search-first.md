# READY_MEALS_ONLY Search-First Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For `READY_MEALS_ONLY` households, resolve real Silpo products *before* asking Claude to build a
week's menu, so every generated meal is already a real, addable product — 0 unresolved by construction,
where today it's 16 of 16.

**Architecture:** New `ReadyMealCatalogService` searches the live Silpo catalog with fixed Ukrainian
dish-category terms via the already-integrated `silpo_find_products_batch` tool, using a cart context
resolved eagerly (`CartBuildingService.getOrCreateCartContext` + `firstDeliverableSlot`). `MealPlanService`
feeds the resulting candidates to Claude as a closed list to curate from (never invent from), and stamps
the chosen candidate's real `productId` onto the returned `PlannedIngredient`. `CartBuildingService`
skips the name-search step entirely for any shopping list line that already carries a `productId`.

**Tech Stack:** Spring Boot 4 / Java, JPA + Liquibase, MapStruct, JUnit 5 + Mockito + AssertJ,
Testcontainers (Postgres) for `*IntegrationTest`, in-process stub HTTP servers (`StubMcpServer`,
`StubAnthropicServer`) for MCP/Claude in integration tests.

**Spec:** `docs/superpowers/specs/2026-09-03-ready-meals-search-first-design.md`

## Global Constraints

- Liquibase owns the schema — `ddl-auto: validate`. New column needs a changeset under
  `src/main/resources/db/changelog/changes/`, next sequential number after `019-...` (i.e. `020-...`).
- Constructor injection only (`@RequiredArgsConstructor`, no `@Autowired` fields) — ArchUnit-enforced.
- `Service` suffix, reachable only from `Controller`/`Job` per ArchUnit (all new code here is
  `service`-package-to-`service`-package, so this doesn't add a new violation surface).
- `@Slf4j` for logging, never `LoggerFactory` directly.
- Run `make format` before the final commit (Spotless/palantir); CI runs `spotlessCheck` before `build`.
- Every task ends green on `./gradlew test` (Docker must be running — Testcontainers).
- No changes to the `COOKS_DAILY`/`COOKS_BATCH` path or to onboarding UI (out of scope per the spec).

---

## Task 1: `silpo_product_id` column, entity, mapper

**Files:**
- Create: `src/main/resources/db/changelog/changes/020-shopping-list-item-silpo-product-id.yaml`
- Modify: `src/main/java/com/silporestockai/entity/ShoppingListItem.java`
- Modify: `src/main/java/com/silporestockai/mapper/ShoppingListItemMapper.java`

**Interfaces:**
- Produces: `ShoppingListItem.getSilpoProductId()` / `.setSilpoProductId(String)` (Lombok-generated),
  used by Task 3 (mapper wiring) and Task 6 (`CartBuildingService`).

- [ ] **Step 1: Write the migration**

```yaml
databaseChangeLog:
  - changeSet:
      id: 020-shopping-list-item-silpo-product-id
      author: komora
      comment: >-
        READY_MEALS_ONLY generation (task 22) now resolves a real Silpo product at plan-generation time,
        so the shopping list line already carries the id CartBuildingService needs to add it to the cart
        directly. Nullable because only that fork ever sets it — RECIPE_DERIVED lines still resolve by
        name search as before.
      changes:
        - addColumn:
            tableName: shopping_list_item
            columns:
              - column:
                  name: silpo_product_id
                  type: VARCHAR(64)
```

- [ ] **Step 2: Add the entity field**

In `ShoppingListItem.java`, add after the existing `sourceType` field (before the closing `}`):

```java
    @Column(name = "silpo_product_id", length = 64)
    private String silpoProductId;
```

- [ ] **Step 3: Wire the mapper**

In `ShoppingListItemMapper.java`, add one more `@Mapping` line to `toItem`:

```java
    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(target = "mealPlanId", source = "mealPlanId")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "category", source = "ingredient.category")
    @Mapping(target = "silpoProductId", source = "ingredient.productId")
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "sourceType", ignore = true)
    ShoppingListItem toItem(PlannedIngredient ingredient, UUID mealPlanId, UUID userId);
```

(`ingredient.productId` doesn't exist yet — Task 2 adds it. This step will not compile until Task 2 is
done; that's expected, do Task 2 immediately after.)

- [ ] **Step 4: Compile-check after Task 2 lands, then commit**

```bash
git add src/main/resources/db/changelog/changes/020-shopping-list-item-silpo-product-id.yaml \
        src/main/java/com/silporestockai/entity/ShoppingListItem.java \
        src/main/java/com/silporestockai/mapper/ShoppingListItemMapper.java
git commit -m "$(cat <<'EOF'
Add a nullable silpo_product_id column to shopping_list_item

READY_MEALS_ONLY generation (task 22) resolves a real product at plan
time; this is where that id is stored so cart-building can skip the
name-search step for these lines.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR
EOF
)"
```

(Committed together with Task 2 in practice, since the mapper only compiles once both land — see Task 2's
own commit step, which supersedes this one. Skip this step's `git commit` if Task 2 is done in the same
sitting; just stage these files there instead.)

---

## Task 2: `PlannedIngredient.productId`

**Files:**
- Modify: `src/main/java/com/silporestockai/model/PlannedIngredient.java`
- Modify: `src/main/java/com/silporestockai/service/ShoppingListService.java:154-203`
- Modify: `src/test/java/com/silporestockai/ShoppingListAggregationTest.java`
- Modify: `src/test/java/com/silporestockai/integration/ShoppingListIntegrationTest.java`
- Modify: `src/test/java/com/silporestockai/unit/MealPlanServiceTest.java:132`

**Interfaces:**
- Produces: `PlannedIngredient(String name, BigDecimal quantity, String unit, String category, String productId)`
  — every later task that builds or reads a `PlannedIngredient` uses this 5-arg shape. `productId` is
  `null` for every `COOKS_DAILY`/`COOKS_BATCH`-derived ingredient.

- [ ] **Step 1: Add the field**

Replace the whole file `PlannedIngredient.java`:

```java
package com.silporestockai.model;

import java.math.BigDecimal;

/**
 * One ingredient of a planned meal.
 *
 * <p>{@code quantity} is a {@link BigDecimal} because task 08 turns these into a shopping list and then into a real
 * Silpo order — quantities that reach a shop must not have been through a {@code double}.
 *
 * @param name the ingredient as a person would write it on a list, in Ukrainian
 * @param quantity how much is needed for the meal
 * @param unit the unit the quantity is in, e.g. {@code кг}, {@code шт}, {@code л}
 * @param category a short category label from the fixed taxonomy the system prompt gives (e.g. "Молочні продукти"),
 *     or null when the model left it out — {@link com.silporestockai.service.CategoryKeywordFallbackService} fills
 *     that gap.
 * @param productId Silpo's real product id, set only by the {@code READY_MEALS_ONLY} generation fork (task 22) once
 *     Claude's choice has been matched back to the candidate it was chosen from — null for every recipe-derived
 *     ingredient, which still resolves to a product by name search in {@code CartBuildingService}.
 */
public record PlannedIngredient(String name, BigDecimal quantity, String unit, String category, String productId) {}
```

- [ ] **Step 2: Fix the three call sites in `ShoppingListService.java`**

`withFallbackCategory` (around line 158, currently ends the file's private helper):

```java
    private PlannedIngredient withFallbackCategory(PlannedIngredient ingredient) {
        if (ingredient.category() != null && !ingredient.category().isBlank()) {
            return ingredient;
        }
        return new PlannedIngredient(
                ingredient.name(), ingredient.quantity(), ingredient.unit(),
                categoryKeywordFallbackService.categorize(ingredient.name()), ingredient.productId());
    }
```

`aggregate`'s merge seed (inside the `for` loop):

```java
            String key = normalise(ingredient.name()) + "|" + normalise(ingredient.unit());
            byNameAndUnit.merge(
                    key,
                    new PlannedIngredient(
                            ingredient.name().trim(), ingredient.quantity(), ingredient.unit(), ingredient.category(),
                            ingredient.productId()),
                    ShoppingListService::add);
```

`add`'s merge result — keeps the first line's `productId`, same rule already used for name/unit/category:

```java
    private static PlannedIngredient add(PlannedIngredient existing, PlannedIngredient extra) {
        BigDecimal quantity;
        if (existing.quantity() == null) {
            quantity = extra.quantity();
        } else if (extra.quantity() == null) {
            quantity = existing.quantity();
        } else {
            quantity = existing.quantity().add(extra.quantity());
        }
        String category =
                existing.category() != null && !existing.category().isBlank() ? existing.category() : extra.category();
        return new PlannedIngredient(existing.name(), quantity, existing.unit(), category, existing.productId());
    }
```

- [ ] **Step 3: Fix the remaining call sites (tests + Task 1's mapper dependency)**

`src/test/java/com/silporestockai/ShoppingListAggregationTest.java` — both `of(...)` overloads pass
`null` for the new param, and add a fifth-arg overload for the new productId test in Task 3:

```java
    private static PlannedIngredient of(String name, String quantity, String unit) {
        return new PlannedIngredient(name, quantity == null ? null : new BigDecimal(quantity), unit, null, null);
    }

    private static PlannedIngredient of(String name, String quantity, String unit, String category) {
        return new PlannedIngredient(name, quantity == null ? null : new BigDecimal(quantity), unit, category, null);
    }

    private static PlannedIngredient of(String name, String quantity, String unit, String category, String productId) {
        return new PlannedIngredient(name, quantity == null ? null : new BigDecimal(quantity), unit, category, productId);
    }
```

`src/test/java/com/silporestockai/integration/ShoppingListIntegrationTest.java` — 4 call sites at (old)
lines 140-142 and 156, each gains a trailing `null`:

```java
                        new PlannedIngredient("попкорн", new BigDecimal("2"), "шт", null, null),
                        new PlannedIngredient("попкорн", new BigDecimal("1"), "шт", null, null),
                        new PlannedIngredient("кола", new BigDecimal("1.5"), "л", null, null)));
```
and
```java
                userId, List.of(new PlannedIngredient("морозиво", BigDecimal.ONE, "шт", null, null)));
```

`src/test/java/com/silporestockai/unit/MealPlanServiceTest.java:132`:

```java
        List<PlannedIngredient> ingredients = List.of(new PlannedIngredient("Щось", BigDecimal.ONE, "шт", "Інше", null));
```

- [ ] **Step 4: Run the full test suite to confirm nothing else references the old 4-arg constructor**

```bash
./gradlew test --tests "com.silporestockai.ShoppingListAggregationTest" \
                --tests "com.silporestockai.integration.ShoppingListIntegrationTest" \
                --tests "com.silporestockai.unit.MealPlanServiceTest"
```

Expected: compiles and all pass. If the compiler finds another call site not listed above, fix it the
same way (append `null` as the 5th argument) — the constructor signature is now load-bearing everywhere.

- [ ] **Step 5: Commit (together with Task 1)**

```bash
git add src/main/java/com/silporestockai/model/PlannedIngredient.java \
        src/main/java/com/silporestockai/service/ShoppingListService.java \
        src/main/java/com/silporestockai/entity/ShoppingListItem.java \
        src/main/java/com/silporestockai/mapper/ShoppingListItemMapper.java \
        src/main/resources/db/changelog/changes/020-shopping-list-item-silpo-product-id.yaml \
        src/test/java/com/silporestockai/ShoppingListAggregationTest.java \
        src/test/java/com/silporestockai/integration/ShoppingListIntegrationTest.java \
        src/test/java/com/silporestockai/unit/MealPlanServiceTest.java
git commit -m "$(cat <<'EOF'
Carry a real Silpo productId on PlannedIngredient through to the list

Nullable end to end — every COOKS_DAILY/COOKS_BATCH ingredient keeps
producing null here, unchanged. This is the data-model half of task
22's search-first fix; ReadyMealCatalogService and MealPlanService are
what will actually set it.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR
EOF
)"
```

---

## Task 3: `productId` survives aggregation — test

**Files:**
- Modify: `src/test/java/com/silporestockai/ShoppingListAggregationTest.java`
- Modify: `src/test/java/com/silporestockai/integration/ShoppingListIntegrationTest.java`

**Interfaces:**
- Consumes: `PlannedIngredient` 5-arg constructor and `ShoppingListService.aggregate` (Task 2),
  `ShoppingListService.deriveFromMealPlan` (existing).

- [ ] **Step 1: Write the failing unit test — same product picked on two different days merges, id kept**

Add to `ShoppingListAggregationTest.java`:

```java
    @Test
    void keepsTheProductIdWhenTheSameReadyMealIsPickedTwice() {
        List<PlannedIngredient> aggregated = ShoppingListService.aggregate(List.of(
                of("Плов з куркою готовий", "1", "порція", "Готові страви", "p-42"),
                of("Плов з куркою готовий", "1", "порція", "Готові страви", "p-42")));

        assertThat(aggregated).hasSize(1);
        assertThat(aggregated.getFirst().productId()).isEqualTo("p-42");
        assertThat(aggregated.getFirst().quantity()).isEqualByComparingTo("2");
    }
```

- [ ] **Step 2: Run it, confirm it fails only if Task 2 wasn't done — otherwise it should already pass**

```bash
./gradlew test --tests "com.silporestockai.ShoppingListAggregationTest.keepsTheProductIdWhenTheSameReadyMealIsPickedTwice"
```

Expected: PASS (Task 2 already made `add()` keep `existing.productId()` — this step is verifying that
decision, not implementing new behavior).

- [ ] **Step 3: Write the failing integration test — the id reaches the stored entity**

Add to `ShoppingListIntegrationTest.java`, reusing the file's existing `Map`-based plan-building style:

```java
    @Test
    void readyMealPlanCarriesTheRealProductIdThroughToTheStoredLine() {
        User user = userAccountService.findOrCreate(8304L);
        Map<String, Object> plan = Map.of(
                "days",
                List.of(Map.of(
                        "day", "MONDAY",
                        "meals", List.of(Map.of(
                                "type", "LUNCH",
                                "name", "Плов з куркою готовий",
                                "ingredients", List.of(Map.of(
                                        "name", "Плов з куркою готовий",
                                        "quantity", BigDecimal.ONE,
                                        "unit", "порція",
                                        "category", "Готові страви",
                                        "productId", "p-42")))))));
        MealPlan saved = mealPlanRepository.save(MealPlan.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .weekStartDate(LocalDate.of(2026, 8, 31))
                .plan(plan)
                .createdAt(Instant.now())
                .build());

        List<ShoppingListItem> items = shoppingListService.deriveFromMealPlan(
                saved.getId(), com.silporestockai.model.ShoppingListSourceType.READY_MEAL_DIRECT);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getSilpoProductId()).isEqualTo("p-42");
    }
```

- [ ] **Step 4: Run it**

```bash
./gradlew test --tests "com.silporestockai.integration.ShoppingListIntegrationTest.readyMealPlanCarriesTheRealProductIdThroughToTheStoredLine"
```

Expected: PASS (Task 1's mapper mapping is what makes this pass — if it fails, re-check the
`@Mapping(target = "silpoProductId", source = "ingredient.productId")` line from Task 1 Step 3).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/silporestockai/ShoppingListAggregationTest.java \
        src/test/java/com/silporestockai/integration/ShoppingListIntegrationTest.java
git commit -m "$(cat <<'EOF'
Test that a real Silpo productId survives aggregation and persistence

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR
EOF
)"
```

---

## Task 4: `ReadyMealCatalogService` (Step A)

**Files:**
- Create: `src/main/java/com/silporestockai/service/ReadyMealCatalogService.java`
- Create: `src/test/java/com/silporestockai/unit/ReadyMealCatalogServiceTest.java`

**Interfaces:**
- Consumes: `SilpoMcpClient.callTool(String, Map<String,Object>, UUID)` (existing),
  `CartBuildingService.getOrCreateCartContext(UUID)` → `CartContext`,
  `CartBuildingService.firstDeliverableSlot(UUID, CartContext)` → `OfferedSlot` (existing, both public).
- Produces: `ReadyMealCatalogService.CatalogCandidate(String name, String productId, String companyId, String branchId, java.math.BigDecimal price)`
  and `List<CatalogCandidate> findCandidates(UUID userId)` — consumed by `MealPlanService` (Task 5).

- [ ] **Step 1: Write the failing unit test**

```java
package com.silporestockai.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.model.CartContext;
import com.silporestockai.model.OfferedSlot;
import com.silporestockai.service.CartBuildingService;
import com.silporestockai.service.ReadyMealCatalogService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReadyMealCatalogServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final CartContext CONTEXT =
            new CartContext("cart-1", "branch-7", "company-3", "delivery", "2026-09-07T10:00:00Z", "2026-09-07T12:00:00Z");

    private SilpoMcpClient silpoMcpClient;
    private CartBuildingService cartBuildingService;
    private ReadyMealCatalogService service;

    private void setUp() {
        silpoMcpClient = mock(SilpoMcpClient.class);
        cartBuildingService = mock(CartBuildingService.class);
        when(cartBuildingService.getOrCreateCartContext(USER_ID)).thenReturn(CONTEXT);
        when(cartBuildingService.firstDeliverableSlot(USER_ID, CONTEXT))
                .thenReturn(new OfferedSlot("slot-1", "slot-1", null));
        service = new ReadyMealCatalogService(silpoMcpClient, cartBuildingService);
    }

    @Test
    void resolvesCartContextThenSearchesTheFixedCategoryTermsInOneCall() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("""
                        {"queries":[{"query":"салат готовий","products":[\
                        {"name":"Салат Цезар готовий","productId":"p-1","companyId":"company-3","branchId":"branch-7","price":89.9}]}]}""",
                        null, false));

        List<ReadyMealCatalogService.CatalogCandidate> candidates = service.findCandidates(USER_ID);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().name()).isEqualTo("Салат Цезар готовий");
        assertThat(candidates.getFirst().productId()).isEqualTo("p-1");
        assertThat(candidates.getFirst().price()).isEqualByComparingTo("89.9");
        verify(cartBuildingService).getOrCreateCartContext(USER_ID);
        verify(cartBuildingService).firstDeliverableSlot(USER_ID, CONTEXT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> argsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(silpoMcpClient).callTool(eq("silpo_find_products_batch"), argsCaptor.capture(), eq(USER_ID));
        assertThat(argsCaptor.getValue().get("branchId")).isEqualTo("branch-7");
        assertThat(argsCaptor.getValue().get("products"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isNotEmpty();
    }

    @Test
    void flattensEveryProductAcrossEveryQueryNotJustTheFirstMatch() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("""
                        {"queries":[\
                        {"query":"салат готовий","products":[\
                        {"name":"Салат Цезар готовий","productId":"p-1"},\
                        {"name":"Салат Грецький готовий","productId":"p-2"}]},\
                        {"query":"борщ готовий","products":[{"name":"Борщ готовий, порція","productId":"p-3"}]}]}""",
                        null, false));

        List<ReadyMealCatalogService.CatalogCandidate> candidates = service.findCandidates(USER_ID);

        assertThat(candidates).extracting(ReadyMealCatalogService.CatalogCandidate::productId)
                .containsExactlyInAnyOrder("p-1", "p-2", "p-3");
    }

    @Test
    void dedupesTheSameProductIdReturnedByTwoDifferentSearchTerms() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("""
                        {"queries":[\
                        {"query":"готові страви","products":[{"name":"Плов з куркою","productId":"p-9"}]},\
                        {"query":"плов готовий","products":[{"name":"Плов з куркою","productId":"p-9"}]}]}""",
                        null, false));

        List<ReadyMealCatalogService.CatalogCandidate> candidates = service.findCandidates(USER_ID);

        assertThat(candidates).hasSize(1);
    }

    @Test
    void returnsAnEmptyListRatherThanFailingWhenTheCatalogHasNothing() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse("{\"queries\":[]}", null, false));

        assertThat(service.findCandidates(USER_ID)).isEmpty();
    }

    @Test
    void throwsWhenTheToolReportsAnError() {
        setUp();
        when(silpoMcpClient.callTool(eq("silpo_find_products_batch"), any(), eq(USER_ID)))
                .thenReturn(new McpToolResponse(null, null, true));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.findCandidates(USER_ID))
                .isInstanceOf(CartBuildException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew test --tests "com.silporestockai.unit.ReadyMealCatalogServiceTest"
```

Expected: FAIL — `ReadyMealCatalogService` does not exist yet.

- [ ] **Step 3: Write the implementation**

```java
package com.silporestockai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.client.mcp.McpToolResponse;
import com.silporestockai.client.mcp.SilpoMcpClient;
import com.silporestockai.exception.CartBuildException;
import com.silporestockai.model.CartContext;
import com.silporestockai.utils.McpResponses;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Step A of the {@code READY_MEALS_ONLY} pipeline (task 22): finds real, currently-available Silpo products
 * before any AI call, so {@code MealPlanService} has something real to curate from instead of inventing dish
 * names that would later fail to match.
 *
 * <p>Reuses {@code silpo_find_products_batch} — the only product-search tool this application has ever
 * integrated or observed live — rather than assuming an unverified, undocumented category-browse tool exists.
 * Unlike {@link CartBuildingService#resolveProducts}, which keeps only the first match per search term because
 * it is resolving a specific requested item, this keeps every product returned for every term: it is building
 * a candidate pool, not resolving named items.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadyMealCatalogService {

    private static final String TOOL_FIND_PRODUCTS = "silpo_find_products_batch";

    /** Ukrainian dish-category search terms, drawn from the ready-meals prompt's own vocabulary. Fixed, not
     * profile-dependent — dietary constraints are applied by Claude at curation time, not by narrowing this list. */
    private static final List<String> CATEGORY_SEARCH_TERMS = List.of(
            "готові страви",
            "салат готовий",
            "гарячий обід готовий",
            "консерви готові до вживання",
            "заморожені готові обіди",
            "випічка готова",
            "сендвіч готовий",
            "суп готовий",
            "борщ готовий",
            "плов готовий");

    private final SilpoMcpClient silpoMcpClient;
    private final CartBuildingService cartBuildingService;

    /** One real product on offer right now, from Step A's search. */
    public record CatalogCandidate(String name, String productId, String companyId, String branchId, BigDecimal price) {}

    /**
     * Every real ready-to-eat product Silpo currently offers this household's branch, deduplicated by
     * {@code productId} — the same product can legitimately answer more than one search term.
     */
    public List<CatalogCandidate> findCandidates(UUID userId) {
        CartContext context = cartBuildingService.getOrCreateCartContext(userId);
        // Same fail-fast convention CartBuildingService.buildCart uses: a household nothing can be delivered to
        // should not spend an AI call curating a menu it can never actually order.
        cartBuildingService.firstDeliverableSlot(userId, context);

        JsonNode found = call(
                userId,
                TOOL_FIND_PRODUCTS,
                Map.of(
                        "branchId", nullSafe(context.branchId()),
                        "deliveryType", nullSafe(context.deliveryType()),
                        "timeslotStart", nullSafe(context.timeslotStart()),
                        "timeslotEnd", nullSafe(context.timeslotEnd()),
                        "products", CATEGORY_SEARCH_TERMS));

        Map<String, CatalogCandidate> byProductId = new LinkedHashMap<>();
        for (JsonNode query : McpResponses.findArray(found, McpResponses.QUERIES)) {
            for (JsonNode product : McpResponses.findArray(query, McpResponses.PRODUCTS)) {
                String productId = McpResponses.findString(product, McpResponses.PRODUCT_ID).orElse(null);
                String name = McpResponses.findString(product, McpResponses.NAME).orElse(null);
                if (productId == null || name == null) {
                    continue;
                }
                byProductId.putIfAbsent(
                        productId,
                        new CatalogCandidate(
                                name,
                                productId,
                                McpResponses.findString(product, McpResponses.COMPANY_ID).orElse(context.companyId()),
                                McpResponses.findString(product, McpResponses.BRANCH_ID).orElse(context.branchId()),
                                McpResponses.findNumber(product, McpResponses.PRICE).orElse(null)));
            }
        }
        log.info(
                "MCP <- {} distinct ready-meal candidates from {} search terms",
                byProductId.size(),
                CATEGORY_SEARCH_TERMS.size());
        return List.copyOf(byProductId.values());
    }

    private JsonNode call(UUID userId, String tool, Map<String, Object> arguments) {
        log.info("MCP -> {} {}", tool, arguments);
        McpToolResponse response = silpoMcpClient.callTool(tool, arguments, userId);
        if (response.isError()) {
            throw new CartBuildException("Silpo tool %s reported an error".formatted(tool));
        }
        return McpResponses.tree(response);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests "com.silporestockai.unit.ReadyMealCatalogServiceTest"
```

Expected: PASS, all 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/ReadyMealCatalogService.java \
        src/test/java/com/silporestockai/unit/ReadyMealCatalogServiceTest.java
git commit -m "$(cat <<'EOF'
Add ReadyMealCatalogService: search Silpo's real catalog before Claude

Step A of task 22's fix. Reuses silpo_find_products_batch — the only
product-search tool this app has ever integrated — with fixed Ukrainian
dish-category terms, and resolves cart context eagerly since the search
needs a branchId/deliveryType/timeslot that today only exist once a
cart exists.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR
EOF
)"
```

---

## Task 5: Wire `MealPlanService` to curate from real candidates

**Files:**
- Modify: `src/main/java/com/silporestockai/service/MealPlanService.java`
- Modify: `src/main/resources/prompts/meal-plan-ready-meals-system.txt`
- Modify: `src/test/java/com/silporestockai/unit/MealPlanServiceTest.java`

**Interfaces:**
- Consumes: `ReadyMealCatalogService.findCandidates(UUID)` → `List<CatalogCandidate>` (Task 4).
- Produces: `MealPlanService` constructor gains a trailing `ReadyMealCatalogService readyMealCatalogService`
  parameter — any other test constructing `MealPlanService` directly needs this 8th argument.

- [ ] **Step 1: Rewrite the ready-meals system prompt to describe curation, not invention**

Replace `src/main/resources/prompts/meal-plan-ready-meals-system.txt` in full:

```
Ти складаєш тижневе меню готової їжі для родини в Україні, яка не готує сама, ОБИРАЮЧИ страви з
реального списку товарів Сільпо, який тобі нададуть у повідомленні користувача. Пишеш українською.

Правила:
- Рівно 7 днів: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY. Кожен день рівно один раз.
- Мінімум 3 прийоми їжі на день: BREAKFAST, LUNCH, DINNER. SNACK додавай лише якщо в родині є діти.
- Кожен прийом їжі — це РІВНО ОДИН товар зі списку кандидатів, який тобі надали. НІКОЛИ не вигадуй
  назву страви — обирай ТІЛЬКИ з наведеного списку і копіюй name ТОЧНО, символ у символ, як у списку.
- У відповіді кожна страва — це один ingredient: name — назва ТОЧНО як у списку кандидатів, quantity —
  завжди 1, unit — "порція" або "шт", category — одна з "Молочні продукти", "М'ясо і птиця", "Риба і
  морепродукти", "Овочі і фрукти", "Крупи і бакалія", "Хлібобулочні вироби", "Яйця", "Готові страви",
  "Інше".
- Обмеження та алергії з профілю — абсолютні. Не обирай зі списку кандидатів страву, назва якої вказує
  на порушення цих обмежень.
- Продукти, які в родині не їдять, не обирай теж.
- Меню має бути різноманітним настільки, наскільки дозволяє список кандидатів: різні страви в різні дні.
  Якщо кандидатів мало, повторення — це нормально, це краще, ніж вигадана страва.
- Продукти зі списку «не пропонуй» не обирай.
- Бюджет — орієнтир, а не жорстке обмеження.
- Ніяких коментарів, пояснень чи тексту поза структурою відповіді.
```

- [ ] **Step 2: Update the existing prompt-selection tests for the new constructor shape and candidate flow**

In `MealPlanServiceTest.java`, replace `assertGenerationUsesPrompt` and add a `readyMealPlan()` helper
next to the existing `validPlan()`:

```java
    private void assertGenerationUsesPrompt(CookingTimePreference preference, String expectedPromptMarker) {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .cookingTimePreference(preference)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));

        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        when(mealPlanRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());

        boolean readyMealsOnly = preference == CookingTimePreference.READY_MEALS_ONLY;
        ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
        if (readyMealsOnly) {
            when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        }

        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(readyMealsOnly ? readyMealPlan() : validPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()),
                readyMealCatalogService);

        service.generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient)
                .completeStructured(systemPromptCaptor.capture(), anyString(), eq(WeeklyMealPlan.class));
        assertThat(systemPromptCaptor.getValue()).isEqualTo(expectedPromptMarker);
    }

    private static List<com.silporestockai.service.ReadyMealCatalogService.CatalogCandidate> oneCandidate() {
        return List.of(new com.silporestockai.service.ReadyMealCatalogService.CatalogCandidate(
                "Салат Цезар готовий", "p-1", "company-3", "branch-7", new BigDecimal("89.90")));
    }

    private static WeeklyMealPlan readyMealPlan() {
        List<PlannedIngredient> ingredients =
                List.of(new PlannedIngredient("Салат Цезар готовий", BigDecimal.ONE, "порція", "Готові страви", null));
        List<PlannedMeal> meals = List.of(
                new PlannedMeal(MealType.BREAKFAST, "Салат Цезар готовий", ingredients),
                new PlannedMeal(MealType.LUNCH, "Салат Цезар готовий", ingredients),
                new PlannedMeal(MealType.DINNER, "Салат Цезар готовий", ingredients));
        List<PlannedDay> days =
                Arrays.stream(DayOfWeek.values()).map(day -> new PlannedDay(day, meals)).toList();
        return new WeeklyMealPlan(days);
    }
```

Add the one new import at the top of the file (`java.math.BigDecimal` is already imported):

```java
import com.silporestockai.service.ReadyMealCatalogService;
```

Update the second constructor call site (`householdCompositionChangesTheGeneratedPromptText`, uses
`COOKS_DAILY`) to pass a plain mock as the 8th argument:

```java
        MealPlanService service = new MealPlanService(
                userProfileRepository,
                mealPlanRepository,
                claudeApiClient,
                inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()),
                mock(ReadyMealCatalogService.class));
```

- [ ] **Step 3: Run the (currently failing to compile) tests to confirm the compile error names the missing constructor param**

```bash
./gradlew compileTestJava
```

Expected: FAIL — `MealPlanService` has no 8-arg constructor yet.

- [ ] **Step 4: Add the 4 new curation-specific tests to `MealPlanServiceTest.java`**

```java
    @Test
    void readyMealsOnlyCurationPromptListsRealCandidatesWithPrice() {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .cookingTimePreference(CookingTimePreference.READY_MEALS_ONLY)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        when(mealPlanRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(readyMealPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository, mealPlanRepository, claudeApiClient, inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()), readyMealCatalogService);

        service.generateWeeklyPlan(USER_ID);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(claudeApiClient)
                .completeStructured(anyString(), userPromptCaptor.capture(), eq(WeeklyMealPlan.class));
        assertThat(userPromptCaptor.getValue())
                .contains("Салат Цезар готовий")
                .contains("89.9");
    }

    @Test
    void readyMealsOnlyResolvesTheRealProductIdOntoTheStoredPlan() {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .cookingTimePreference(CookingTimePreference.READY_MEALS_ONLY)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        ArgumentCaptor<com.silporestockai.entity.MealPlan> savedCaptor =
                ArgumentCaptor.forClass(com.silporestockai.entity.MealPlan.class);
        when(mealPlanRepository.save(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(readyMealPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository, mealPlanRepository, claudeApiClient, inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()), readyMealCatalogService);

        service.generateWeeklyPlan(USER_ID);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        WeeklyMealPlan stored = mapper.convertValue(savedCaptor.getValue().getPlan(), WeeklyMealPlan.class);
        assertThat(stored.days().getFirst().meals())
                .allSatisfy(meal -> assertThat(meal.ingredients().getFirst().productId()).isEqualTo("p-1"));
    }

    @Test
    void readyMealsOnlyRetriesWhenClaudeInventsAProductOutsideTheCandidateList() {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .cookingTimePreference(CookingTimePreference.READY_MEALS_ONLY)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        when(mealPlanRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(oneCandidate());
        List<PlannedIngredient> invented =
                List.of(new PlannedIngredient("Страва, якої нема в каталозі", BigDecimal.ONE, "порція", "Готові страви", null));
        List<PlannedMeal> inventedMeals = List.of(
                new PlannedMeal(MealType.BREAKFAST, "Вигадка", invented),
                new PlannedMeal(MealType.LUNCH, "Вигадка", invented),
                new PlannedMeal(MealType.DINNER, "Вигадка", invented));
        WeeklyMealPlan inventedPlan = new WeeklyMealPlan(
                Arrays.stream(DayOfWeek.values()).map(day -> new PlannedDay(day, inventedMeals)).toList());
        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);
        when(claudeApiClient.completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class)))
                .thenReturn(inventedPlan)
                .thenReturn(readyMealPlan());

        MealPlanService service = new MealPlanService(
                userProfileRepository, mealPlanRepository, claudeApiClient, inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()), readyMealCatalogService);

        service.generateWeeklyPlan(USER_ID);

        Mockito.verify(claudeApiClient, Mockito.times(2))
                .completeStructured(anyString(), anyString(), eq(WeeklyMealPlan.class));
    }

    @Test
    void readyMealsOnlyThrowsWithoutCallingClaudeWhenNoCandidatesExist() {
        UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
        UserProfile profile = UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .cookingTimePreference(CookingTimePreference.READY_MEALS_ONLY)
                .build();
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        MealPlanRepository mealPlanRepository = mock(MealPlanRepository.class);
        InventoryTrendService inventoryTrendService = mock(InventoryTrendService.class);
        when(inventoryTrendService.getRemovalCandidates(USER_ID)).thenReturn(List.of());
        ReadyMealCatalogService readyMealCatalogService = mock(ReadyMealCatalogService.class);
        when(readyMealCatalogService.findCandidates(USER_ID)).thenReturn(List.of());
        ClaudeApiClient claudeApiClient = mock(ClaudeApiClient.class);

        MealPlanService service = new MealPlanService(
                userProfileRepository, mealPlanRepository, claudeApiClient, inventoryTrendService,
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
                new ByteArrayResource("RECIPE-PROMPT".getBytes()),
                new ByteArrayResource("READY-MEALS-PROMPT".getBytes()), readyMealCatalogService);

        assertThatThrownBy(() -> service.generateWeeklyPlan(USER_ID))
                .isInstanceOf(com.silporestockai.exception.MealPlanGenerationException.class);
        Mockito.verifyNoInteractions(claudeApiClient);
    }
```

Add the missing static import at the top of the file:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 5: Implement `MealPlanService` — the actual change**

In `MealPlanService.java`:

Add imports:
```java
import com.silporestockai.exception.MealPlanGenerationException; // already imported
import com.silporestockai.service.ReadyMealCatalogService.CatalogCandidate;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
```

Add the field and constructor parameter (append after `inventoryTrendService`):

```java
    private final InventoryTrendService inventoryTrendService;
    private final ReadyMealCatalogService readyMealCatalogService;
    private final Clock clock;
```

```java
    public MealPlanService(
            UserProfileRepository userProfileRepository,
            MealPlanRepository mealPlanRepository,
            ClaudeApiClient claudeApiClient,
            InventoryTrendService inventoryTrendService,
            Clock clock,
            @Value("classpath:prompts/meal-plan-system.txt") Resource recipeSystemPromptResource,
            @Value("classpath:prompts/meal-plan-ready-meals-system.txt") Resource readyMealsSystemPromptResource,
            ReadyMealCatalogService readyMealCatalogService) {
        this.userProfileRepository = userProfileRepository;
        this.mealPlanRepository = mealPlanRepository;
        this.claudeApiClient = claudeApiClient;
        this.inventoryTrendService = inventoryTrendService;
        this.readyMealCatalogService = readyMealCatalogService;
        this.clock = clock;
        this.recipeSystemPrompt = read(recipeSystemPromptResource);
        this.readyMealsSystemPrompt = read(readyMealsSystemPromptResource);
    }
```

Replace the whole `generate` method:

```java
    private MealPlan generate(UUID userId, String adjustment) {
        UserProfile profile = userProfileRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ApplicationException(
                        HttpStatus.PRECONDITION_REQUIRED,
                        "user %s has no profile yet; onboarding has to finish first".formatted(userId)));

        boolean readyMealsOnly = profile.getCookingTimePreference() == CookingTimePreference.READY_MEALS_ONLY;
        String systemPrompt = readyMealsOnly ? readyMealsSystemPrompt : recipeSystemPrompt;
        List<String> untouched = inventoryTrendService.getRemovalCandidates(userId);

        List<CatalogCandidate> candidates = List.of();
        String userPrompt;
        if (readyMealsOnly) {
            candidates = readyMealCatalogService.findCandidates(userId);
            if (candidates.isEmpty()) {
                // Zero real candidates means there is nothing for Claude to curate — asking it anyway would
                // just reproduce the original bug in a new form (an invented dish with no candidates behind it).
                throw new MealPlanGenerationException(
                        userId, List.of("Сільпо не має готових страв, які підходять під ваші обмеження цього тижня"));
            }
            userPrompt = curationPrompt(profile, adjustment, untouched, candidates);
        } else {
            userPrompt = describe(profile, adjustment, untouched);
        }

        WeeklyMealPlan plan = claudeApiClient.completeStructured(systemPrompt, userPrompt, WeeklyMealPlan.class);
        List<String> defects = allDefectsOf(plan, readyMealsOnly, candidates);
        if (!defects.isEmpty()) {
            // One retry, naming what was wrong. Re-sending the same prompt would be a coin flip, and the transport
            // retries in ClaudeApiClientImpl do not see this class of failure at all — the answer arrived fine, it is
            // the plan inside it that is unusable.
            log.warn("Claude returned an unusable plan for user {}: {}", userId, defects);
            plan = claudeApiClient.completeStructured(
                    systemPrompt, correctionOf(userPrompt, defects), WeeklyMealPlan.class);
            defects = allDefectsOf(plan, readyMealsOnly, candidates);
            if (!defects.isEmpty()) {
                throw new MealPlanGenerationException(userId, defects);
            }
        }
        if (readyMealsOnly) {
            plan = withResolvedProductIds(plan, candidates);
        }
        return persist(
                userId,
                plan,
                readyMealsOnly ? ShoppingListSourceType.READY_MEAL_DIRECT : ShoppingListSourceType.RECIPE_DERIVED);
    }

    private static List<String> allDefectsOf(WeeklyMealPlan plan, boolean readyMealsOnly, List<CatalogCandidate> candidates) {
        List<String> defects = new ArrayList<>(defectsOf(plan));
        if (readyMealsOnly) {
            defects.addAll(candidateDefects(plan, candidates));
        }
        return defects;
    }

    /** Every ingredient name Claude returned that is not, character for character (case-insensitive), one of the
     * real candidates it was given — the check the acceptance criteria call "never invents outside the list". */
    private static List<String> candidateDefects(WeeklyMealPlan plan, List<CatalogCandidate> candidates) {
        if (plan == null || plan.days() == null) {
            return List.of();
        }
        Set<String> candidateNames =
                candidates.stream().map(candidate -> normalise(candidate.name())).collect(Collectors.toSet());
        List<String> defects = new ArrayList<>();
        for (PlannedDay day : plan.days()) {
            List<PlannedMeal> meals = day == null || day.meals() == null ? List.of() : day.meals();
            for (PlannedMeal meal : meals) {
                List<PlannedIngredient> ingredients =
                        meal == null || meal.ingredients() == null ? List.of() : meal.ingredients();
                for (PlannedIngredient ingredient : ingredients) {
                    String name = ingredient == null ? null : ingredient.name();
                    if (name == null || !candidateNames.contains(normalise(name))) {
                        defects.add("«%s» немає у списку реальних товарів Сільпо".formatted(name));
                    }
                }
            }
        }
        return defects;
    }

    /** Stamps each ingredient's real productId on, matching by the same case-insensitive name rule as
     * {@link #candidateDefects}. Only ever called once that check has already passed — every name is guaranteed
     * to have a match. */
    private static WeeklyMealPlan withResolvedProductIds(WeeklyMealPlan plan, List<CatalogCandidate> candidates) {
        Map<String, CatalogCandidate> byName = candidates.stream()
                .collect(Collectors.toMap(candidate -> normalise(candidate.name()), candidate -> candidate, (a, b) -> a));
        List<PlannedDay> days = plan.days().stream()
                .map(day -> new PlannedDay(
                        day.day(),
                        day.meals().stream()
                                .map(meal -> new PlannedMeal(
                                        meal.type(),
                                        meal.name(),
                                        meal.ingredients().stream()
                                                .map(ingredient -> new PlannedIngredient(
                                                        ingredient.name(),
                                                        ingredient.quantity(),
                                                        ingredient.unit(),
                                                        ingredient.category(),
                                                        Objects.requireNonNull(byName.get(normalise(ingredient.name())))
                                                                .productId()))
                                                .toList()))
                                .toList()))
                .toList();
        return new WeeklyMealPlan(days);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
```

Add `curationPrompt`, right after `describe`:

```java
    /** {@link #describe}'s household/constraints text, plus the real candidates Claude must choose from — never a
     * separate copy of the household text, since the constraints apply identically to both generation paths. */
    private String curationPrompt(UserProfile profile, String adjustment, List<String> untouched, List<CatalogCandidate> candidates) {
        StringBuilder text = new StringBuilder(describe(profile, adjustment, untouched));
        text.append("\nОсь список готових страв, які зараз реально є в Сільпо. Обирай страви ТІЛЬКИ з цього ")
                .append("списку і вказуй name страви ТОЧНО так, як він написаний нижче:\n");
        int position = 1;
        for (CatalogCandidate candidate : candidates) {
            text.append(position++).append(". ").append(candidate.name());
            if (candidate.price() != null) {
                text.append(" (").append(candidate.price().toPlainString()).append(" грн)");
            }
            text.append('\n');
        }
        return text.toString();
    }
```

- [ ] **Step 6: Run all `MealPlanServiceTest` tests**

```bash
./gradlew test --tests "com.silporestockai.unit.MealPlanServiceTest"
```

Expected: PASS, all tests (old and new).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/silporestockai/service/MealPlanService.java \
        src/main/resources/prompts/meal-plan-ready-meals-system.txt \
        src/test/java/com/silporestockai/unit/MealPlanServiceTest.java
git commit -m "$(cat <<'EOF'
Curate READY_MEALS_ONLY plans from real Silpo candidates, not invention

MealPlanService now calls ReadyMealCatalogService first, asks Claude to
choose only from the returned real products, validates every returned
name is one of them (retrying once on a miss, same convention as every
other plan defect), and stamps the matched productId onto the stored
ingredient. Zero candidates short-circuits before any Claude call.

Fixes the 0-of-16 unresolved bug: a READY_MEALS_ONLY plan's ingredients
now carry a real productId by construction, so CartBuildingService has
nothing left to guess at.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR
EOF
)"
```

---

## Task 6: `CartBuildingService` skips search for pre-resolved lines

**Files:**
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java:333-379`
- Modify: `src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java`

**Interfaces:**
- Consumes: `ShoppingListItem.getSilpoProductId()` (Task 1).
- No signature change to `resolveProducts` — same public method, different internal behavior.

- [ ] **Step 1: Write the failing integration test — this is the closest thing to the original bug repro**

Add to `CartBuildingIntegrationTest.java`, near `mergesTwoRequestedLinesThatMatchedTheSameProduct`:

```java
    private static ShoppingListItem readyMealItem(String name, String productId) {
        return ShoppingListItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .quantity(BigDecimal.ONE)
                .unit("порція")
                .silpoProductId(productId)
                .build();
    }

    /**
     * The exact bug this fixes: task 22's production evidence was 16 of 16 READY_MEALS_ONLY items unresolved
     * because CartBuildingService tried to name-search invented dish descriptions. With a real productId already
     * on the line, cart-building must add it directly — no search, no chance of a miss.
     */
    @Test
    void addsAPreResolvedReadyMealDirectlyWithoutSearchingForItByName() {
        UUID userId = connectedUser(8418L);
        scriptCartTools();
        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        scriptVerifiedCart();

        List<ShoppingListItem> items = List.of(
                readyMealItem("Салат Цезар готовий", "p-1"),
                readyMealItem("Борщ готовий, порція", "p-2"));

        cartBuildingService.buildCart(userId, items);

        assertThat(MCP.calledTools()).doesNotContain("silpo_find_products_batch");
        JsonNode added = MCP.callArguments("silpo_add_or_update_cart_products").getFirst();
        assertThat(added.path("products")).hasSize(2);
    }

    @Test
    void mixesPreResolvedReadyMealsWithSearchedRecipeIngredientsInOneCart() {
        UUID userId = connectedUser(8419L);
        scriptCartTools();
        scriptProductTools();
        scriptVerifiedCart();

        List<ShoppingListItem> items =
                List.of(readyMealItem("Салат Цезар готовий", "p-9"), item("цибуля", "0.5", "кг"));

        cartBuildingService.buildCart(userId, items);

        assertThat(MCP.calledTools()).contains("silpo_find_products_batch");
        JsonNode search = MCP.callArguments("silpo_find_products_batch").getFirst();
        // Only the unresolved line was ever searched for — the pre-resolved one never appears in the query.
        assertThat(search.path("products")).hasSize(1);
        assertThat(search.path("products").get(0).asText()).isEqualTo("цибуля");
        JsonNode added = MCP.callArguments("silpo_add_or_update_cart_products").getFirst();
        assertThat(added.path("products")).hasSize(2);
    }
```

- [ ] **Step 2: Run to verify both fail**

```bash
./gradlew test --tests "com.silporestockai.integration.CartBuildingIntegrationTest"
```

Expected: FAIL — `ShoppingListItem.builder().silpoProductId(...)` doesn't compile yet only if Task 1 wasn't
done; if Task 1 is done, this compiles but `addsAPreResolvedReadyMealDirectlyWithoutSearchingForItByName`
fails because `silpo_find_products_batch` is still called for every item today.

- [ ] **Step 3: Implement the partition in `resolveProducts`**

Replace the method body in `CartBuildingService.java` (keep the same signature and javadoc):

```java
    public List<ResolvedProduct> resolveProducts(UUID userId, CartContext context, List<ShoppingListItem> items) {
        List<ResolvedProduct> resolved = new ArrayList<>();
        List<ShoppingListItem> preResolved = items.stream()
                .filter(item -> item.getSilpoProductId() != null && !item.getSilpoProductId().isBlank())
                .toList();
        // READY_MEALS_ONLY lines already carry a real productId, resolved during generation (task 22) — adding
        // them straight to the cart, not searching for them again, is the whole point of that fix.
        for (ShoppingListItem item : preResolved) {
            resolved.add(new ResolvedProduct(
                    item.getName(),
                    item.getSilpoProductId(),
                    context.companyId(),
                    context.branchId(),
                    quantityOf(item),
                    item.getUnit()));
        }

        List<ShoppingListItem> needsSearch = items.stream()
                .filter(item -> item.getSilpoProductId() == null || item.getSilpoProductId().isBlank())
                .toList();
        for (int start = 0; start < needsSearch.size(); start += SEARCH_BATCH_SIZE) {
            List<ShoppingListItem> chunk =
                    needsSearch.subList(start, Math.min(needsSearch.size(), start + SEARCH_BATCH_SIZE));
            JsonNode found = call(
                    userId,
                    TOOL_FIND_PRODUCTS,
                    Map.of(
                            "branchId", nullSafe(context.branchId()),
                            "deliveryType", nullSafe(context.deliveryType()),
                            "timeslotStart", nullSafe(context.timeslotStart()),
                            "timeslotEnd", nullSafe(context.timeslotEnd()),
                            "products", chunk.stream().map(ShoppingListItem::getName).toList()));
            for (JsonNode query : McpResponses.findArray(found, McpResponses.QUERIES)) {
                String queryText = McpResponses.findString(query, McpResponses.NAME).orElse(null);
                ShoppingListItem item = queryText == null
                        ? null
                        : chunk.stream()
                                .filter(candidate -> candidate.getName().equalsIgnoreCase(queryText))
                                .findFirst()
                                .orElse(null);
                if (item == null) {
                    continue;
                }
                McpResponses.findArray(query, McpResponses.PRODUCTS).stream()
                        .findFirst()
                        .ifPresent(product -> {
                            String productId = McpResponses.findString(product, McpResponses.PRODUCT_ID)
                                    .orElse(null);
                            if (productId == null) {
                                return;
                            }
                            resolved.add(new ResolvedProduct(
                                    item.getName(),
                                    productId,
                                    McpResponses.findString(product, McpResponses.COMPANY_ID)
                                            .orElse(context.companyId()),
                                    McpResponses.findString(product, McpResponses.BRANCH_ID)
                                            .orElse(context.branchId()),
                                    cartQuantity(item, product),
                                    item.getUnit()));
                        });
            }
        }
        log.info(
                "MCP <- resolved {} of {} shopping list lines ({} pre-resolved, {} searched)",
                resolved.size(),
                items.size(),
                preResolved.size(),
                needsSearch.size());
        return resolved;
    }
```

- [ ] **Step 4: Run the tests again**

```bash
./gradlew test --tests "com.silporestockai.integration.CartBuildingIntegrationTest"
```

Expected: PASS, all tests including the two new ones and every pre-existing one (e.g.
`runsAllSixCallsInTheDocumentedOrder` still expects exactly one `silpo_find_products_batch` call for a
plain recipe item with no `silpoProductId`, unaffected by the partition).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/CartBuildingService.java \
        src/test/java/com/silporestockai/integration/CartBuildingIntegrationTest.java
git commit -m "$(cat <<'EOF'
Skip silpo_find_products_batch for shopping list lines with a real id

A line already carrying silpoProductId (set by the READY_MEALS_ONLY
fork, task 22) goes straight into the add-to-cart call; only lines
still needing resolution go through the existing search loop. Both
kinds can share one cart-build call, as the recipe-plus-ready-meal
regression test shows.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR
EOF
)"
```

---

## Task 7: Honest message when candidates were sparse

**Files:**
- Modify: `src/main/java/com/silporestockai/service/MealPlanHandoffService.java`
- Modify: `src/test/java/com/silporestockai/integration/MealPlanHandoffIntegrationTest.java`

**Interfaces:**
- Consumes: `PlannedIngredient.productId()` (Task 2), `MealPlan.getSourceType()` (existing),
  `ShoppingListSourceType.READY_MEAL_DIRECT` (existing).

- [ ] **Step 1: Write the failing integration test**

Add to `MealPlanHandoffIntegrationTest.java` — needs a `READY_MEALS_ONLY` profile and a plan whose
ingredients repeat the same 2 distinct products across 7 days (well under the 7-distinct threshold):

```java
    private UUID readyMealsProfiledUser() {
        User user = userAccountService.findOrCreate(CHAT_ID);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .cookingTimePreference(com.silporestockai.model.CookingTimePreference.READY_MEALS_ONLY)
                .build());
        return user.getId();
    }

    private static String sparseReadyMealsWeekJson() {
        StringBuilder days = new StringBuilder();
        String[] names = {"Салат Цезар готовий", "Борщ готовий, порція"};
        String[] ids = {"p-1", "p-2"};
        int i = 0;
        for (DayOfWeek day : DayOfWeek.values()) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            String name = names[i % 2];
            String productId = ids[i % 2];
            i++;
            days.append("""
                    {"day":"%s","meals":[\
                    {"type":"BREAKFAST","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція",\
                    "category":"Готові страви","productId":"%s"}]},\
                    {"type":"LUNCH","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція",\
                    "category":"Готові страви","productId":"%s"}]},\
                    {"type":"DINNER","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція",\
                    "category":"Готові страви","productId":"%s"}]}]}"""
                    .formatted(day.name(), name, name, productId, name, name, productId, name, name, productId));
        }
        return "{\"days\":[" + days + "]}";
    }

    @Test
    void warnsWhenTheReadyMealsWeekHadToRepeatBecauseFewRealCandidatesExisted() {
        UUID userId = readyMealsProfiledUser();
        CLAUDE.respondWithText(sparseReadyMealsWeekJson());

        mealPlanHandoffService.generateFirstPlan(userId);

        assertThat(TELEGRAM.sentMessages().getFirst().path("text").asText())
                .contains("не так багато готових страв");
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./gradlew test --tests "com.silporestockai.integration.MealPlanHandoffIntegrationTest.warnsWhenTheReadyMealsWeekHadToRepeatBecauseFewRealCandidatesExisted"
```

Expected: FAIL — the message doesn't mention it yet.

- [ ] **Step 3: Implement the caveat in `summarise`**

Replace `summarise` in `MealPlanHandoffService.java` and add the two helpers:

```java
    private static final int MINIMUM_DISTINCT_READY_MEALS = 7;

    /** One line: the week is ready, and here is Monday, which is the only part anyone reads immediately. */
    private static String summarise(MealPlan plan, int shoppingListSize) {
        WeeklyMealPlan week = MAPPER.convertValue(plan.getPlan(), WeeklyMealPlan.class);
        String monday = week.days().stream()
                .filter(day -> day.day() == DayOfWeek.MONDAY)
                .findFirst()
                .map(PlannedDay::meals)
                .orElse(List.of())
                .stream()
                .map(PlannedMeal::name)
                .collect(Collectors.joining(" / "));
        String message = "План на тиждень готовий, %d днів.\nПонеділок: %s\nСписок покупок: %d позицій."
                .formatted(week.days().size(), monday, shoppingListSize);
        if (plan.getSourceType() == ShoppingListSourceType.READY_MEAL_DIRECT
                && distinctRealProducts(week) < MINIMUM_DISTINCT_READY_MEALS) {
            // Derived from what actually ended up in the plan, not a separate flag from generation — a thin
            // candidate pool and a repetitive week are the same thing in practice.
            message += "\nЧерез ваші обмеження знайшлось не так багато готових страв, тому деякі повторюються "
                    + "цього тижня.";
        }
        return message;
    }

    private static long distinctRealProducts(WeeklyMealPlan week) {
        return week.days().stream()
                .flatMap(day -> (day.meals() == null ? List.<PlannedMeal>of() : day.meals()).stream())
                .flatMap(meal -> (meal.ingredients() == null ? List.<PlannedIngredient>of() : meal.ingredients()).stream())
                .map(PlannedIngredient::productId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
    }
```

Add the two new imports:

```java
import com.silporestockai.model.PlannedIngredient;
import com.silporestockai.model.ShoppingListSourceType;
```

- [ ] **Step 4: Run the new test and the whole file**

```bash
./gradlew test --tests "com.silporestockai.integration.MealPlanHandoffIntegrationTest"
```

Expected: PASS, all tests including `generatesAPlanAndTellsTheUserWhatIsOnMonday` (a `COOKS_DAILY`-style
plan, `sourceType` `RECIPE_DERIVED` — the new `if` never triggers, message unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/silporestockai/service/MealPlanHandoffService.java \
        src/test/java/com/silporestockai/integration/MealPlanHandoffIntegrationTest.java
git commit -m "$(cat <<'EOF'
Tell the user honestly when a thin candidate pool forced repeats

Acceptance criterion from task 22: a restrictive-diet + READY_MEALS_ONLY
combination with few real candidates must produce an honest message,
not a silently padded or broken plan. Derived from the persisted plan's
own distinct-productId count rather than a new flag threaded through
generation — the two are the same signal.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR
EOF
)"
```

---

## Task 8: Full round-trip integration test (closest automated bug repro)

**Files:**
- Create: `src/test/java/com/silporestockai/integration/ReadyMealsSearchFirstIntegrationTest.java`

**Interfaces:**
- Consumes: `MealPlanService.generateWeeklyPlan(UUID)`, `ShoppingListService.deriveFromMealPlan(UUID, ShoppingListSourceType)`,
  `CartBuildingService.buildCart(UUID, List<ShoppingListItem>)` — all existing/updated, wired by Spring.

This is the closest thing to repeating the exact manual test that surfaced the bug (generate a
`READY_MEALS_ONLY` plan → build the cart → confirm 0 unresolved) that can run without a live Telegram +
Silpo account. It combines `StubMcpServer` (Silpo) and `StubAnthropicServer` (Claude) in one Spring
context, following the patterns already used separately in `CartBuildingIntegrationTest` and
`MealPlanHandoffIntegrationTest`.

- [ ] **Step 1: Write the test**

```java
package com.silporestockai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.silporestockai.entity.MealPlan;
import com.silporestockai.entity.ShoppingListItem;
import com.silporestockai.entity.SilpoOAuthToken;
import com.silporestockai.entity.User;
import com.silporestockai.entity.UserProfile;
import com.silporestockai.model.CartSummary;
import com.silporestockai.model.CookingTimePreference;
import com.silporestockai.model.ShoppingListSourceType;
import com.silporestockai.repository.SilpoOAuthTokenRepository;
import com.silporestockai.repository.UserProfileRepository;
import com.silporestockai.repository.UserRepository;
import com.silporestockai.service.CartBuildingService;
import com.silporestockai.service.MealPlanService;
import com.silporestockai.service.ShoppingListService;
import com.silporestockai.service.UserAccountService;
import com.silporestockai.support.StubAnthropicServer;
import com.silporestockai.support.StubMcpServer;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Repeats, as closely as an automated test can, the manual test that surfaced task 22's bug: a
 * READY_MEALS_ONLY household generates a plan and tries to build a cart from it. Production evidence was 16
 * of 16 items unresolved; this asserts 0.
 */
@DisplayName("READY_MEALS_ONLY: generate a plan, then actually build the cart from it")
class ReadyMealsSearchFirstIntegrationTest extends AbstractIntegrationTest {

    private static final StubMcpServer MCP = startMcp();
    private static final StubAnthropicServer CLAUDE = startClaude();

    @Autowired
    private MealPlanService mealPlanService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private CartBuildingService cartBuildingService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private SilpoOAuthTokenRepository tokenRepository;

    @Autowired
    private TokenCipher tokenCipher;

    private static StubMcpServer startMcp() {
        try {
            return new StubMcpServer(List.of(
                    "silpo_get_my_shopping_cart",
                    "silpo_get_shopping_cart_by_id",
                    "silpo_get_time_slots",
                    "silpo_find_products_batch",
                    "silpo_add_or_update_cart_products"));
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
        registry.add("silpo.mcp.endpoint", MCP::endpoint);
        registry.add("claude.api-key", () -> "sk-ant-stub-key");
        registry.add("claude.base-url", CLAUDE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        MCP.close();
        CLAUDE.close();
    }

    @BeforeEach
    void clean() {
        MCP.reset();
        CLAUDE.reset();
        tokenRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    private UUID readyMealsUser(long chatId) {
        User user = userAccountService.findOrCreate(chatId);
        userProfileRepository.save(UserProfile.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .householdSize(2)
                .cookingTimePreference(CookingTimePreference.READY_MEALS_ONLY)
                .build());
        tokenRepository.save(SilpoOAuthToken.builder()
                .userId(user.getId())
                .accessToken(tokenCipher.encrypt("stub-access-token"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user.getId();
    }

    private void scriptSilpo() {
        MCP.respondToTool("silpo_get_my_shopping_cart", "{\"cartId\":\"cart-1\"}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3",\
                "deliveryType":"delivery","items":[]}""");
        MCP.respondToTool("silpo_get_time_slots", "{\"timeSlots\":[{\"id\":\"slot-1\",\"from\":\"18:00\"}]}");
        // 16 candidates across a few search terms — the same order of magnitude as the production bug report.
        MCP.respondToTool("silpo_find_products_batch", """
                {"queries":[{"query":"готові страви","products":[
                {"name":"Сир кисломолочний з ягодами, порція","productId":"p-1"},
                {"name":"Салат «Грецький» готовий","productId":"p-2"},
                {"name":"Гречка з яловичиною готова страва","productId":"p-3"},
                {"name":"Йогурт натуральний грецький","productId":"p-4"},
                {"name":"Плов з куркою готовий","productId":"p-5"},
                {"name":"Борщ готовий, порція","productId":"p-6"},
                {"name":"Салат Цезар готовий","productId":"p-7"},
                {"name":"Суп-пюре гарбузовий готовий","productId":"p-8"},
                {"name":"Котлета по-київськи готова","productId":"p-9"},
                {"name":"Рагу овочеве готове","productId":"p-10"},
                {"name":"Сендвіч з куркою готовий","productId":"p-11"},
                {"name":"Круасан з шинкою готовий","productId":"p-12"},
                {"name":"Консерви тунець готові до вживання","productId":"p-13"},
                {"name":"Запіканка сирна готова, порція","productId":"p-14"},
                {"name":"Плов вегетаріанський готовий","productId":"p-15"},
                {"name":"Салат з тунцем готовий","productId":"p-16"}]}]}"""
                .replace("\n", ""));
    }

    private static String curatedWeekJson() {
        // The exact ready-meal names from the stubbed catalog above, echoed back verbatim — what a correctly
        // curating Claude does, and what the new ready-meals system prompt asks for.
        String[] names = {
            "Сир кисломолочний з ягодами, порція", "Салат «Грецький» готовий", "Гречка з яловичиною готова страва",
            "Йогурт натуральний грецький", "Плов з куркою готовий", "Борщ готовий, порція", "Салат Цезар готовий"
        };
        StringBuilder days = new StringBuilder();
        String[] dayNames = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"};
        for (int d = 0; d < 7; d++) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            String breakfast = names[d % names.length];
            String lunch = names[(d + 1) % names.length];
            String dinner = names[(d + 2) % names.length];
            days.append("""
                    {"day":"%s","meals":[\
                    {"type":"BREAKFAST","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція","category":"Готові страви"}]},\
                    {"type":"LUNCH","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція","category":"Готові страви"}]},\
                    {"type":"DINNER","name":"%s","ingredients":[{"name":"%s","quantity":1,"unit":"порція","category":"Готові страви"}]}]}"""
                    .formatted(dayNames[d], breakfast, breakfast, lunch, lunch, dinner, dinner));
        }
        return "{\"days\":[" + days + "]}";
    }

    @Test
    void everyGeneratedItemResolvesAndTheCartActuallyBuilds() {
        UUID userId = readyMealsUser(8420L);
        scriptSilpo();
        CLAUDE.respondWithText(curatedWeekJson());

        MealPlan plan = mealPlanService.generateWeeklyPlan(userId);
        List<ShoppingListItem> items =
                shoppingListService.deriveFromMealPlan(plan.getId(), plan.getSourceType());

        assertThat(plan.getSourceType()).isEqualTo(ShoppingListSourceType.READY_MEAL_DIRECT);
        assertThat(items).isNotEmpty();
        assertThat(items).allSatisfy(item -> assertThat(item.getSilpoProductId()).isNotBlank());

        MCP.respondToTool("silpo_add_or_update_cart_products", "{\"ok\":true}");
        MCP.respondToTool("silpo_get_shopping_cart_by_id", """
                {"cartId":"cart-1","branchId":"branch-7","companyId":"company-3","deliveryType":"delivery",\
                "items":[],"total":0,"validations":[]}""");

        CartSummary summary = cartBuildingService.buildCart(userId, items);

        assertThat(summary.unresolved()).isEmpty();
        JsonNode added = MCP.callArguments("silpo_add_or_update_cart_products").getFirst();
        assertThat(added.path("products")).hasSize(items.size());
        // Only Step A's own search, during generation — none during cart-building for these pre-resolved lines.
        assertThat(MCP.callCount("silpo_find_products_batch")).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it**

```bash
./gradlew test --tests "com.silporestockai.integration.ReadyMealsSearchFirstIntegrationTest"
```

Expected: PASS. If it fails on `curatedWeekJson`'s names not matching the stub catalog exactly, check for
a stray character mismatch (e.g. the «» quote marks) — the whole point of this test is that name-matching
is exact.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/silporestockai/integration/ReadyMealsSearchFirstIntegrationTest.java
git commit -m "$(cat <<'EOF'
Add a full generate-then-build-cart regression test for task 22

Replays the production bug report's own 16 unresolved item names as
the stubbed catalog, generates a READY_MEALS_ONLY plan against them,
and builds a real cart from the result — the closest automated
equivalent to the manual test that found the bug.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR
EOF
)"
```

---

## Task 9: Full regression run, format, docs

**Files:**
- Modify: `docs/RUNBOOK.md` (add a short manual-test note for `READY_MEALS_ONLY`)
- No other files — this task is verification + one doc addition.

- [ ] **Step 1: Run the full test suite**

```bash
./gradlew test
```

Expected: PASS, every test — in particular every existing `COOKS_DAILY`/`COOKS_BATCH`-relevant test
(`MealPlanServiceTest`'s non-ready-meals cases, `ShoppingListIntegrationTest`,
`CartBuildingIntegrationTest`'s original tests) green and unmodified in behavior. This is the acceptance
criterion "COOKS_DAILY/COOKS_BATCH path test coverage still passes unchanged."

- [ ] **Step 2: Run Spotless**

```bash
make format
./gradlew spotlessCheck
```

Expected: no diffs after `make format`; `spotlessCheck` passes.

- [ ] **Step 3: Add a short manual-test note to `docs/RUNBOOK.md`**

Find the section documenting the cart-building manual test (search the file for
`silpo_find_products_batch` around line 266) and add, immediately after it:

```markdown
### READY_MEALS_ONLY: verify the search-first fix (task 22)

For a household with `cookingTimePreference = READY_MEALS_ONLY`, the generated plan's every ingredient
must already carry a real `productId` — check `meal_plan.plan_json` directly:

```bash
docker exec -i app-db psql -U app -d app <<'SQL'
SELECT plan_json -> 'days' -> 0 -> 'meals' -> 0 -> 'ingredients' -> 0 ->> 'productId'
FROM meal_plan WHERE user_id = (SELECT id FROM users WHERE telegram_chat_id = <CHAT_ID>)
ORDER BY created_at DESC LIMIT 1;
SQL
```

A non-null value here, followed by a cart that actually builds with 0 `unresolved` (see
`CartSummary.unresolved()` / the bot's own cart message), confirms the fix. The log line
`Silpo matched no product for N of M items` should read `0 of M` — the exact line the original bug report
quoted at `16 of 16`.
```

- [ ] **Step 4: Commit the doc addition**

```bash
git add docs/RUNBOOK.md
git commit -m "$(cat <<'EOF'
Document the READY_MEALS_ONLY manual verification step for task 22

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QEGRU6Mwnr9M7u8R5A2suR
EOF
)"
```

- [ ] **Step 5: Report readiness for the live manual test**

Nothing to commit here — this step is a checklist for whoever runs the live check per
`docs/LOCAL_TUNNEL.md` / `docs/RUNBOOK.md`: reset a `READY_MEALS_ONLY` test account, regenerate its plan,
confirm the shopping list, and watch for the `Silpo matched no product for 0 of N items` log line.
