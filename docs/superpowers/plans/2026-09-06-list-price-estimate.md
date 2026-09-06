# Price estimate in the shopping-list preview (task 39) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The list a household reads *before* a cart is built carries an honest price estimate, from data already in hand — no new MCP calls.

**Architecture:** The cart confirmation (task 10) already prints per-line prices and «Разом» — criterion 1 was already true. The gap is one stage earlier: `ShoppingListMessageService.listText` (the «Список» view, the plan hand-off, «Показати весь список») shows quantities only. Two price sources exist without calling Silpo: (a) `READY_MEALS_ONLY` plans are curated from `CatalogCandidate`s that carry a price — carried onto `PlannedIngredient.price` and persisted as `shopping_list_item.estimated_price`; (b) for everything else, the current `baseline_basket` holds the line price this household last paid for the same item name. A small pure `ShoppingListPriceEstimateService` folds both into a `PriceEstimate(total, pricedCount, unpricedCount)`; the message says «Орієнтовно ~X грн» and how many lines had no price. Lines with neither source stay unpriced — never a guessed number.

**Tech Stack:** Liquibase changeset `023`, JPA, MapStruct mapper, Spring service, JUnit unit tests + existing integration tests.

**Spec:** Notion task 39 (`3d27227d-ef1c-8117-8d40-d91ebded65a8`); decision in `docs/OVERNIGHT_QUESTIONS.md` → Session 4 → Task 39.

## Global Constraints

- **No new MCP tool calls** (criterion 3). The estimate reads `shopping_list_item` and `baseline_basket` only.
- Liquibase owns the schema: new column → `023-shopping-list-item-estimated-price.yaml`, `ddl-auto: validate`.
- No recipe/day info in the List view (task 20 separation) — price only.
- Copy: «ти», short, honest («орієнтовно», «точну суму покажу в кошику»).
- `make format` before each commit; targeted tests per commit; full `make test` at the end.

---

### Task 1: Carry the catalog price onto the list line

**Files:**
- Create: `src/main/resources/db/changelog/changes/023-shopping-list-item-estimated-price.yaml`
- Modify: `src/main/java/com/silporestockai/entity/ShoppingListItem.java` (new field `estimatedPrice`)
- Modify: `src/main/java/com/silporestockai/model/PlannedIngredient.java` (new component `price`, 5-arg convenience constructor)
- Modify: `src/main/java/com/silporestockai/mapper/ShoppingListItemMapper.java` (`estimatedPrice ← ingredient.price`)
- Modify: `src/main/java/com/silporestockai/service/MealPlanService.java` (`withResolvedProductIds` sets price from the candidate; `withoutProductIds` nulls it)
- Modify: `src/main/java/com/silporestockai/service/ShoppingListService.java` (`aggregate`/`add`/`withFallbackCategory` keep the price)
- Test: `src/test/java/com/silporestockai/unit/MealPlanServiceTest.java`, `src/test/java/com/silporestockai/ShoppingListAggregationTest.java`

**Interfaces:**
- Produces: `PlannedIngredient(String name, BigDecimal quantity, String unit, String category, String productId, BigDecimal price)` plus the old 5-arg constructor delegating with `price = null`; `ShoppingListItem.getEstimatedPrice()`.

- [ ] **Step 1: Failing tests** — in `MealPlanServiceTest`, in the existing READY_MEALS_ONLY happy-path test, assert the stored plan's ingredient carries the candidate's price (`price` key in the persisted plan map); in `ShoppingListAggregationTest`, assert that two lines of the same ready meal keep the first line's price after `aggregate`.
- [ ] **Step 2: Run** `./gradlew test --tests '*MealPlanServiceTest' --tests '*ShoppingListAggregationTest'` → FAIL (no `price`).
- [ ] **Step 3: Implement** the changeset (`numeric(10,2)`, nullable, comment: unit price at plan time, only the ready-meals fork sets it), the entity column, the record component + convenience constructor, the mapper mapping, the two `MealPlanService` rewrites, the three `ShoppingListService` copies.
- [ ] **Step 4: Run** the same two test classes → PASS; also `./gradlew test --tests '*ShoppingListIntegrationTest'` (schema validate).
- [ ] **Step 5: Commit** `Carry the catalog price from a ready-meals plan onto its list lines (task 39)`.

---

### Task 2: The estimate itself

**Files:**
- Create: `src/main/java/com/silporestockai/model/PriceEstimate.java`
- Create: `src/main/java/com/silporestockai/service/ShoppingListPriceEstimateService.java`
- Test: `src/test/java/com/silporestockai/unit/ShoppingListPriceEstimateServiceTest.java`

**Interfaces:**
- `record PriceEstimate(BigDecimal total, int pricedCount, int unpricedCount)` with `static PriceEstimate none()` and `boolean hasPrices()`.
- `PriceEstimate ShoppingListPriceEstimateService.estimate(UUID userId, List<ShoppingListItem> items)` (loads the current baseline) and `static PriceEstimate estimate(List<ShoppingListItem> items, List<BasketItem> baseline)` (pure).

Rules (pure method):
1. `item.estimatedPrice != null` → line = price × (quantity, or 1 when null).
2. else a baseline line with the same normalised name (trim, lower-case): if both quantities are non-null and positive and the units match (normalised) → baseline price × item.qty / baseline.qty; otherwise the baseline line price as-is. Baseline lines with a null price are skipped.
3. else unpriced.
Total rounded to 2 dp HALF_UP.

- [ ] **Step 1: Failing test** with four cases: catalog price × quantity; baseline scaled by quantity; baseline used as-is when units differ; nothing priced → `none()`.
- [ ] **Step 2: Run** `./gradlew test --tests '*ShoppingListPriceEstimateServiceTest'` → FAIL.
- [ ] **Step 3: Implement** record + service (`@Service @RequiredArgsConstructor`, `BaselineBasketRepository` injected, `findByUserIdAndIsCurrentTrue(userId).map(BaselineBasket::getItems).orElse(List.of())`).
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `Estimate a shopping list's price from catalog and baseline data, no Silpo call (task 39)`.

---

### Task 3: Say it in the list preview and the plan summary

**Files:**
- Modify: `src/main/java/com/silporestockai/service/telegram/ShoppingListMessageService.java` (`listText(items, estimate)`)
- Modify: `src/main/java/com/silporestockai/service/ShoppingListBuilderService.java` (`present` computes the estimate; constructor gains the service)
- Modify: `src/main/java/com/silporestockai/service/MealPlanHandoffService.java` (`summarise` adds «Орієнтовно ~X грн» when the estimate has prices)
- Test: `src/test/java/com/silporestockai/unit/ShoppingListMessageServiceTest.java`; existing `MealPlanHandoffIntegrationTest` / `ShoppingListBuilder*` tests must stay green.

Copy, appended after «Всього N позицій.»:
- all priced: `Орієнтовно ~1234.50 грн — точну суму покажу в кошику.`
- partial: `Орієнтовно ~800.00 грн за 5 з 12 позицій — точну суму покажу в кошику.`
- none: nothing extra.

Plan summary (hand-off) line, only when `hasPrices()`: `Орієнтовно ~1234.50 грн.` on its own line after «Список покупок: N позицій.».

- [ ] **Step 1: Failing tests** in `ShoppingListMessageServiceTest`: `listText` with a full estimate contains «Орієнтовно ~»; with `none()` does not contain «Орієнтовно»; partial names «за 5 з 12 позицій».
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `ShoppingListMessageServiceTest`, `MealPlanHandoffIntegrationTest`, `ShoppingListIntegrationTest`, `ShoppingListBuilder*` → PASS.
- [ ] **Step 5: Commit** `Show a price estimate on the list before the cart exists (task 39)`.

---

### Task 4: Docs, full gate, Notion

- [ ] `docs/OVERNIGHT_QUESTIONS.md`: criterion 1 was already met; the real gap; baseline fallback rationale; why no MCP lookup.
- [ ] `docs/RUNBOOK.md` section 5: expect the «Орієнтовно» line (ready meals: right away; cooks: from the second list on).
- [ ] `make test` green → Notion 39 → In review (live: does Silpo's candidate `price` field actually populate on a real account — the stub can't prove that).
