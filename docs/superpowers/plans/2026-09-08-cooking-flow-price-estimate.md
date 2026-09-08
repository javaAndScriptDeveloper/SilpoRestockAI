# The cooking flow's half of the price estimate (task 39, follow-up) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A household that cooks sees «Орієнтовно ~X грн» on its list, as task 39's plan said it would. Today it never does.

**What is already true (verified 2026-09-08):** task 39 shipped in `94eb538`. The cart confirmation prints «Разом»
(`CartMessageService`), a `READY_MEALS_ONLY` week carries catalog prices from `CatalogCandidate.price()` through
`PlannedIngredient.price` and `aggregate`/`add`/`withFallbackCategory` into `shopping_list_item.estimated_price`, and
`MealPlanHandoffService.summarise` prints the estimate at the plan-summary stage, before any cart exists. None of that
regressed through the 48–53 refactors, and none of it calls Silpo.

**The gap:** the other source — the current `baseline_basket` — is matched by exact normalised name
(`ShoppingListPriceEstimateService.estimate`). The baseline stores Silpo's catalog names («Молоко «Яготинське» 2,6%
п/е», «Цибуля ріпчаста жовта»); a list stores household names («Молоко», «Цибуля»). The two never compare equal, so
for every household that cooks the estimate is silently empty. Driving the live app on 2026-09-08 (25-line list, a
current 20-line baseline) produced a list message with no price line at all.

**Approach:** stop guessing at the join and record it. `CartBuildingService` already knows which list line each product
was resolved for — `ResolvedProduct.requestedName()`. Carry that onto the basket line, so the next confirmed cart
leaves the baseline with the household's own words next to the catalog price. For baselines already stored (and for a
line whose requested name did not survive), fall back to a deliberately narrow name match: every word of the list name
must appear as a whole word in the catalog name. Anything else stays unpriced and is counted as such — the copy
already says «за 5 з 12 позицій», so under-matching is visible and honest, while a wrong match would quietly inflate a
number somebody reads as real.

**Tech Stack:** Java records with back-compat constructors (the `CartSummary` idiom), Spring services, JUnit.

**Spec:** Notion task 39 (`3d27227d-ef1c-8117-8d40-d91ebded65a8`); the original plan is
`docs/superpowers/plans/2026-09-06-list-price-estimate.md`.

## Global Constraints

- **No new MCP tool calls.** Nothing here talks to Silpo: the requested name is already in memory during a cart build,
  and the estimate reads `shopping_list_item` and `baseline_basket` only. Verify against `mcp_tool_call` before/after.
- `items_json` is stored JSON — the new field must be nullable and every old shape must still deserialise.
- No recipe/day information in the List view (task 20's separation). Price only.
- Copy unchanged: «Орієнтовно ~X грн — точну суму покажу в кошику.»
- `make format` before each commit; `make test` at the end (stop `bootRun` first — shared `build/classes`).

---

### Task 1: Record which list line a basket line came from

**Files:**
- Modify: `src/main/java/com/silporestockai/model/BasketItem.java` (new component `requestedName` + 5-arg constructor)
- Modify: `src/main/java/com/silporestockai/service/CartBuildingService.java` (`build` and `topUp` attach the names)
- Test: `src/test/java/com/silporestockai/unit/BasketItemTest.java` (new), existing `CartBuilding*` tests stay green

**Interfaces:**
- `BasketItem(String silpoProductId, String name, String unit, BigDecimal quantity, BigDecimal price, String requestedName)`
  plus the old 5-arg constructor delegating with `requestedName = null`.
- Private static `CartSummary withRequestedNames(CartSummary, Map<String, String> requestedNameByProductId)` in
  `CartBuildingService`; `build` feeds it the resolution, `topUp` feeds it the previous summary's own names merged with
  the top-up lines, so a line does not lose its name to a second cart read.

- [ ] **Step 1: Failing tests** — a `BasketItem` deserialised from a five-field JSON object has a null
  `requestedName`; a cart built from a list whose line «Молоко» resolved to «Молоко «Яготинське» 2,6% п/е» comes back
  with that line's `requestedName` set to «Молоко».
- [ ] **Step 2: Run** `./gradlew test --tests '*BasketItemTest' --tests '*CartBuilding*'` → FAIL.
- [ ] **Step 3: Implement** the record component, the constructor, and the two attachment points.
- [ ] **Step 4: Run** the same tests → PASS.
- [ ] **Step 5: Commit** `Record which list line each basket line was bought for (task 39)`.

---

### Task 2: Match the baseline the way the data actually looks

**Files:**
- Modify: `src/main/java/com/silporestockai/service/ShoppingListPriceEstimateService.java`
- Test: `src/test/java/com/silporestockai/unit/ShoppingListPriceEstimateServiceTest.java`

Rules, in order, first hit wins:
1. `item.estimatedPrice != null` → line = price × (quantity, or 1 when null). Unchanged.
2. A baseline line whose `requestedName` normalises equal to the item's name.
3. A baseline line whose `name` normalises equal to the item's name. Unchanged, and still the ready-meals case.
4. A baseline line whose catalog name contains **every** word of the item's name as a whole word (case-folded,
   punctuation split out, words of one or two letters ignored). Baseline order breaks ties.
5. Otherwise unpriced.

- [ ] **Step 1: Failing tests** — «Молоко» is priced from «Молоко «Яготинське» 2,6% п/е»; «Цибуля» from «Цибуля
  ріпчаста жовта»; «Куряче філе» is **not** priced from «Філе курчати-бройлера мале охолоджене» (one word missing);
  «Хліб» is **not** priced from «Батон «Київхліб»» (substring, not a word); an exact `requestedName` beats a
  containment match on a different line.
- [ ] **Step 2: Run** `./gradlew test --tests '*ShoppingListPriceEstimateServiceTest'` → FAIL.
- [ ] **Step 3: Implement** the lookup order and the word-containment rule.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `Price a cooking week from the baseline the households actually have (task 39)`.

---

### Task 3: Prove it on the live app, then the docs

- [ ] Drive the running app: re-present the current list (`list:full`) and read `logs/app.log` for the «Орієнтовно»
  line; hand-check the sum against the baseline rows it drew on.
- [ ] Drive a `READY_MEALS_ONLY` plan end to end and check the plan summary carries the estimate before the cart.
- [ ] Count `mcp_tool_call` rows before and after both runs — the estimate must add none.
- [ ] `make test` green (app stopped first).
- [ ] `docs/RUNBOOK.md` section 5: what the cooking flow now shows and from when.
- [ ] `docs/OVERNIGHT_QUESTIONS.md`: why the requested name is recorded rather than the names being matched cleverly.
- [ ] Notion 39 → status, and the demo script's step 4 narration.
