# READY_MEALS_ONLY: search-first generation — design

Notion task 22. Depends on tasks 09 (`CartBuildingService`) and 20 (`READY_MEALS_ONLY` fork).

## Problem

`MealPlanService`'s `READY_MEALS_ONLY` branch asks Claude to invent ready-meal dish names, then
`CartBuildingService` tries to string-match those invented names against the real Silpo catalog via
`silpo_find_products_batch`. Freely-generated names almost never match — production log evidence: 0 of
16 items resolved for a real test user, meaning `READY_MEALS_ONLY` households (people who don't cook, a
core target segment) cannot place an order at all. `COOKS_DAILY`/`COOKS_BATCH` is unaffected: raw
ingredient names ("цибуля", "куряче філе") are broad enough to reliably match.

## Goal

Invert the pipeline for `READY_MEALS_ONLY` only: search the real catalog first, then have Claude curate
from real results. Claude must never invent a product that later needs matching.

## Constraints established during brainstorming

- No live Silpo account is reachable from this environment. `McpResponses`' own javadoc states the exact
  response shape of every tool here has never been observed live — every field lookup is a defensive,
  breadth-first, multi-key-name search for exactly this reason. No category-browse MCP tool is documented
  anywhere in this repo. Only `silpo_find_products_batch` (free-text query strings) is integrated and
  tested. **Decision: reuse it, do not invent an unverified tool name.**
- `silpo_find_products_batch` requires `branchId` / `deliveryType` / `timeslotStart` / `timeslotEnd`,
  which today only exist once a cart exists — and a cart is normally only created when the user confirms
  their shopping list, well after plan generation. **Decision: for `READY_MEALS_ONLY` generation only,
  eagerly resolve cart context** (`CartBuildingService.getOrCreateCartContext` +
  `firstDeliverableSlot`) before searching. Accepted consequence: a `READY_MEALS_ONLY` user with no saved
  Silpo delivery address now fails at *generation* time (clear message) instead of later at cart-confirm
  time. Onboarding already requires connecting a Silpo account before `OnboardingCompletedEvent` fires, so
  this only tightens an existing dependency, it doesn't introduce a new one.
- **Decision:** carry the resolved real product forward via a **nullable `productId` field on
  `PlannedIngredient`** (and a nullable `silpo_product_id` column on `shopping_list_item`), rather than a
  parallel plan-shape for this fork. Keeps `WeeklyMealPlan`/`PlannedDay`/`PlannedMeal` uniform for both
  generation paths, so `MealPlanHandoffService.summarise()` and any future week-over-week diff feature
  don't need to branch on plan shape. Always `null` for `COOKS_DAILY`/`COOKS_BATCH` — zero behavior change
  there.

## Architecture

```
MealPlanService.generate()
  READY_MEALS_ONLY?
    ReadyMealCatalogService.findCandidates(userId)   [Step A — new]
      CartBuildingService.getOrCreateCartContext + firstDeliverableSlot
      silpo_find_products_batch × curated Ukrainian dish-category terms
      → List<CatalogCandidate(name, productId, companyId, branchId, price)>
    empty? → MealPlanGenerationException (reuses the existing apology path), no Claude call
    else  → ClaudeApiClient.completeStructured(readyMealsSystemPrompt, curationPrompt, WeeklyMealPlan.class)
              curationPrompt = profile constraints + numbered candidate list (name, price)
            defectsOf(): existing checks + "every meal name is one of the candidate names"
            map each returned PlannedMeal.name() back to its CatalogCandidate → PlannedIngredient.productId
  COOKS_DAILY / COOKS_BATCH:  unchanged
```

### New: `ReadyMealCatalogService` (package `service`)

Step A only. Depends on `SilpoMcpClient` (via the same `silpo_find_products_batch` tool
`CartBuildingService` already knows) and `CartBuildingService` (for cart-context resolution — both
methods it needs, `getOrCreateCartContext` and `firstDeliverableSlot`, are already public). Unlike
`CartBuildingService.resolveProducts` (keeps only the first match per query, because it's resolving a
specific requested item), this keeps **every** product returned per query — it's building a candidate
pool, not resolving named items.

Curated search terms (Ukrainian, drawn from the existing ready-meals prompt's own vocabulary): готові
страви, салат готовий, гарячий обід готовий, консерви готові до вживання, заморожені готові обіди,
випічка готова, сендвіч готовий, суп готовий, борщ готовий, плов готовий. Fixed list, not
profile-dependent — profile constraints are applied by Claude at curation time, not by the search terms
(a search term list itself does not encode "без лактози").

`record CatalogCandidate(String name, String productId, String companyId, String branchId, BigDecimal price)`

### `MealPlanService` changes

- `describe(...)` unchanged for the recipe path.
- New private `curationPrompt(profile, adjustment, untouched, candidates)` for the ready-meals path:
  same household/constraints text as `describe(...)`, plus a numbered list of candidates (name + price).
  System prompt (`meal-plan-ready-meals-system.txt`) rewritten: Claude must choose ONLY from the given
  numbered list, one candidate per meal slot, and echo the candidate's `name` field **verbatim** — this is
  how the Java side maps the answer back to a `productId` afterward (structured output carries names, not
  opaque ids, because asking Claude to also parse/repeat a UUID correctly is a needless extra failure
  mode when name-matching against a small known list is exact and cheap).
- `defectsOf(plan)` gains a `READY_MEALS_ONLY`-only check (candidate list passed in): every
  `meal.name()` must equal some `candidate.name()` (case-insensitive, mirroring the existing
  `equalsIgnoreCase` convention in `CartBuildingService.resolveProducts`). Violations feed the existing
  one-retry-with-correction loop, same as every other defect today.
- `persist(...)`: for `READY_MEALS_ONLY`, before building the `Map<String,Object>` for storage, resolve
  each `PlannedIngredient` to carry `productId` from the matching candidate (name lookup, same
  case-insensitive rule).
- Zero candidates: short-circuit before any Claude call, throw `MealPlanGenerationException` with a
  single defect message — reuses `MealPlanHandoffService`'s existing catch block and user-facing apology,
  no new exception type.

### Data model

- `PlannedIngredient`: add `String productId` (5th field, nullable, javadoc note explaining it's only
  ever set by the `READY_MEALS_ONLY` fork).
- `shopping_list_item`: new nullable `silpo_product_id VARCHAR` column — new Liquibase changeset
  (`0NN-shopping-list-item-silpo-product-id.yaml`, next number after whatever currently exists in
  `db/changelog/changes/`).
- `ShoppingListItem` entity: `silpoProductId` field.
- `ShoppingListItemMapper`: `@Mapping(target = "silpoProductId", source = "ingredient.productId")`.
- `ShoppingListService.aggregate()`'s private `add(existing, extra)` merge: keep `existing`'s
  `productId` (same rule already used for name/unit/category — first line's identity wins, quantity
  sums). Untouched otherwise — no fork of `deriveFromMealPlan`; the existing aggregation is harmless and
  arguably desirable here (identical ready meal picked on two different days becomes one line, quantity
  2).

### `CartBuildingService.resolveProducts` changes

Partition `items` up front:
- `silpoProductId != null` → build `ResolvedProduct` directly: `requestedName = item.getName()`,
  `productId = item.getSilpoProductId()`, `companyId`/`branchId` from the current `context` (same fallback
  `resolveProducts` already uses for search-derived results), `quantity = quantityOf(item)`,
  `unit = item.getUnit()`. **No MCP call.**
- `silpoProductId == null` → existing chunked `silpo_find_products_batch` loop, unchanged.

Both lists concatenate into the same `resolved` list `buildCart` already works with; `unresolvedNames`
needs no change — it already matches by name against whatever ended up in `resolved`.

### Sparse-candidate honesty (acceptance criterion 5)

No new field, no new exception. `MealPlanHandoffService.summarise()` already re-parses `WeeklyMealPlan`
from the persisted plan; it additionally counts distinct `productId`s actually used across the week. Below
7 (fewer than one distinct ready meal per day) it appends: *"Через ваші обмеження знайшлось не так багато
готових страв, тому деякі повторюються цього тижня."* This is a proxy for "Step A's candidate pool was
thin" derived from the final plan, not the search step directly — accepted as sufficient: the two are the
same thing in practice (a thin pool is the only way Claude ends up repeating).

### Regression

`recipeSystemPrompt` branch: zero changes. `productId` stays `null` throughout that path by construction.
`resolveProducts`' pre-resolved partition is a no-op when `silpoProductId` is always null — behavior
identical to today for every existing test.

## Testing

- Unit — `ReadyMealCatalogService`: sends the fixed search terms, flattens all `queries[].products[]`
  (not just first-per-query) into `CatalogCandidate`s, empty-catalog case returns an empty list, uses
  cart context from `CartBuildingService`.
- Unit — `MealPlanService`: candidate list → curation prompt content; `defectsOf` rejects a plan
  containing a name not in the candidate list and retries; zero candidates short-circuits before any
  `ClaudeApiClient` call; `persist` correctly resolves `productId` per ingredient by name match.
- Unit — `ShoppingListItemMapper` / `ShoppingListService.aggregate`: `productId` flows through
  end-to-end, survives a same-product-two-days merge.
- Unit — `CartBuildingService.resolveProducts`: a pre-resolved item never triggers
  `silpo_find_products_batch`; a mixed list (some pre-resolved, some not) resolves both correctly; existing
  tests for the plain search path pass unchanged.
- Integration (WireMock, alongside `CartBuildingIntegrationTest`'s pattern) — replays the bug scenario:
  16 ready-meal candidates seeded, generate a plan, build the cart, assert 0 unresolved and
  `silpo_add_or_update_cart_products` was called with all 16 real product ids, and that no
  `silpo_find_products_batch` call happens during cart-build (only during generation's Step A).
- Regression — existing `COOKS_DAILY`/`COOKS_BATCH` unit and integration tests re-run unmodified and
  green.
- Manual (out of automation's reach here — needs a real Telegram + Silpo-connected account per
  `docs/RUNBOOK.md`): `READY_MEALS_ONLY` profile → generate → confirm cart → order actually places.

## Out of scope

No changes to `COOKS_DAILY`/`COOKS_BATCH`. No onboarding UI changes (task 23). No precise
`displayRatio`/`step` quantity rounding for pre-resolved items (existing `cartQuantity` logic needs the
live product JSON node from a fresh `silpo_find_products_batch` call, which this design deliberately
skips for these items) — quantity sent as whatever the curation step decided (defaults to 1 per the
existing ready-meals prompt's "quantity always 1" rule); Silpo's own cart API is the backstop if a step
mismatch occurs, same risk class as the nullable/lenient parsing already throughout `McpResponses`.
