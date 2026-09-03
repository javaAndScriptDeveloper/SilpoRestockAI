# Structured onboarding (WebApp form) + persisted list + List/Calendar separation

Notion task: [20. Structured onboarding (WebApp form) + persisted list + List/Calendar separation](https://app.notion.com/p/3d07227def1c811daa61dfe7236cf949)
Depends on: 05 (schema), 06 (onboarding — this reworks it), 08 (shopping list persistence shape)
Out of scope: reorder/checkin flow changes (#11–#15) beyond `status`/`source_type` compatibility; delta display on modifications (#21).

## Context

Two problems surfaced from a live bot review:

1. Onboarding is too thin — one budget question, no structured household/diet/cooking-time collection. `MealPlanService` generates mostly blind.
2. The shopping list renders as an undifferentiated wall of text — no categories, no structure.

Plus one hidden architectural issue: the shopping list currently gets rebuilt (an AI call) on paths that should be pure CRUD, and there's no fork for `READY_MEALS_ONLY` users — the recipe→ingredients→raw-list pipeline doesn't apply to them at all. And recipe/day info leaking into the List view creates real ambiguity (an onion used across three meals — split three ways or shown once?).

## Goal

Replace the single-message text onboarding with a structured, low-friction Telegram WebApp form; persist the shopping list with an explicit status so AI is called only when the list actually needs to change; render the list grouped by category; keep recipe/day info exclusively in the Calendar view; fork meal-plan generation itself (not just its display) for `READY_MEALS_ONLY` users.

## Non-goals

- No changes to reorder/checkin logic beyond keeping it compatible with the new `status`/`source_type` columns.
- No delta-display feature (that's #21).
- No build tooling for the WebApp form — a static HTML/CSS/vanilla-JS page served from Spring, no bundler, no framework.
- No cleanup job for archived shopping-list rows — unbounded growth is acceptable for the hackathon timeframe.

## Architecture

### 1. Onboarding flow

```
AWAITING_CONNECT (unchanged: Silpo enrichment or skip)
        │
        ▼
AWAITING_WEBAPP_FORM  ── web_app button tap, fills form, submits ──▶ parse payload ──▶ ASK_BUDGET
        │
        └── "Заповнити вручну" callback (onb:manual) ──▶ ASK_HOUSEHOLD → ASK_RESTRICTIONS → ASK_DISLIKES (unchanged) ──▶ ASK_BUDGET
```

`OnboardingFlowService.enrichThenConfirm`/`askNext` stop routing straight into `ASK_HOUSEHOLD`; instead they land on a new `AWAITING_WEBAPP_FORM` step that sends a message with two buttons:

- `TelegramButton.webApp("Заповнити анкету", formUrl)` — new factory on `TelegramButton`, sets Telegram's `web_app` button type instead of `url`/`callback_data`.
- `TelegramButton.callback("Заповнити вручну", CALLBACK_MANUAL)` — drops straight into the existing `ASK_HOUSEHOLD` text chain, unchanged.

`formUrl` is `{webAppBaseUrl}/webapp/onboarding.html?prefill={base64url(JSON)}` — the JSON carries whatever `enrichThenConfirm` already resolved (household size, restrictions) so the form opens pre-filled where Silpo already told us. `webAppBaseUrl` is a new `telegram.web-app-base-url` config property; when blank, the WebApp button is not sent at all and the flow goes straight into the manual chain (same "unconfigured integration degrades gracefully" pattern as `GoogleAuthService.configured()`).

The static page (`src/main/resources/static/webapp/onboarding.html` + `onboarding.js`) loads the Telegram WebApp JS SDK (`https://telegram.org/js/telegram-web-app.js`), reads `prefill` from the query string to pre-populate fields, and on submit calls `Telegram.WebApp.sendData(JSON.stringify(payload))`, which closes the WebApp and delivers a `message.web_app_data.data` field on the next update Telegram sends the bot.

Form fields (per spec):

- Adults: male count, female count — two number steppers.
- Children: repeatable row of age-bracket select (`0-3`/`4-7`/`8-12`/`13-17`), one row per child, add/remove row.
- Restrictions/allergies: multi-select checkboxes (nuts, lactose, gluten, seafood) + free-text "other".
- Diet type: single-select (none/vegetarian/vegan/gluten-free/keto/other).
- Cooking time: single-select radio (`COOKS_DAILY`/`COOKS_BATCH`/`READY_MEALS_ONLY`), each with a one-line explanation.

Payload shape (submitted JSON, parsed by `OnboardingFlowService`):

```json
{
  "adultMale": 1, "adultFemale": 1,
  "childrenAgeBrackets": ["AGE_4_7", "AGE_8_12"],
  "restrictions": ["nuts", "lactose"], "restrictionsOther": "",
  "dietType": "VEGETARIAN",
  "cookingTimePreference": "COOKS_BATCH"
}
```

### 2. New incoming-update shape

`TelegramIncomingUpdate` gets a new variant:

```java
record WebAppData(long chatId, long telegramUserId, String data) implements TelegramIncomingUpdate {}
```

`TelegramRoutingService.toIncoming` adds `message.hasWebAppData()` handling (`message.getWebAppData().getData()`) before the existing text/photo/voice checks. `OnboardingFlowService.handle` gets a new `case TelegramIncomingUpdate.WebAppData data -> handleWebAppSubmit(...)` at `AWAITING_WEBAPP_FORM`; every other step treats a stray `WebAppData` update the same as it currently treats a `Voice`/`Photo` at the wrong step — a nudge back to the expected input, not a crash.

### 3. Schema

`018-user-profile-structured-onboarding.yaml`:

| column | type | notes |
|---|---|---|
| `adult_male_count` | int, nullable | |
| `adult_female_count` | int, nullable | |
| `children_age_brackets` | jsonb, nullable | list of `AgeBracket` names |
| `diet_type` | varchar(32), nullable | `DietType` enum name |
| `cooking_time_preference` | varchar(32), nullable | `CookingTimePreference` enum name |

`household_size`, `has_kids`, `kids_ages`, `dietary_restrictions`, `disliked_foods` stay exactly as they are — the manual-fallback chain still writes them directly, and `MealPlanService.describe()` keeps reading them unchanged. When the WebApp path is used, `OnboardingFlowService.finish()` additionally derives `householdSize = adultMale + adultFemale + children.size()` and `hasKids`/`kidsAges` (bracket → representative age, e.g. midpoint, only for the parts of the codebase that still read raw ages) so every existing consumer of those columns keeps working without touching them.

`019-shopping-list-item-status.yaml`:

| column | type | notes |
|---|---|---|
| `status` | varchar(16), not null, default `'ACTIVE'` | `ShoppingListStatus`: `ACTIVE`/`ORDERED`/`ARCHIVED` |
| `source_type` | varchar(24), nullable | `ShoppingListSourceType`: `RECIPE_DERIVED`/`READY_MEAL_DIRECT` |

### 4. New enums (`model` package)

- `AgeBracket { AGE_0_3, AGE_4_7, AGE_8_12, AGE_13_17 }`
- `DietType { NONE, VEGETARIAN, VEGAN, GLUTEN_FREE, KETO, OTHER }`
- `CookingTimePreference { COOKS_DAILY, COOKS_BATCH, READY_MEALS_ONLY }`
- `ShoppingListStatus { ACTIVE, ORDERED, ARCHIVED }`
- `ShoppingListSourceType { RECIPE_DERIVED, READY_MEAL_DIRECT }`
- New `OnboardingStep.AWAITING_WEBAPP_FORM` (inserted between `CONFIRM_PROFILE`/`AWAITING_CONNECT` and `ASK_HOUSEHOLD`).

### 5. Generation fork (`MealPlanService`)

Two system-prompt resources injected instead of one:

```java
@Value("classpath:prompts/meal-plan-system.txt") Resource recipeSystemPromptResource,
@Value("classpath:prompts/meal-plan-ready-meals-system.txt") Resource readyMealsSystemPromptResource
```

`generate()` picks the prompt by `profile.getCookingTimePreference()`. `READY_MEALS_ONLY` (and the `null` default, i.e. legacy profiles from before this task, or the manual-fallback chain which doesn't collect this field) uses the existing recipe prompt — no regression for anyone the form hasn't reached yet.

The new prompt instructs Claude to return the *same* `WeeklyMealPlan`/`PlannedDay`/`PlannedMeal`/`PlannedIngredient` JSON shape, but each meal has exactly one `PlannedIngredient`: the ready-to-eat product to search Silpo for (`name` = e.g. "Салат Цезар готовий", `quantity` = 1, `unit` = "порція" or "шт"). No recipe text, no raw ingredients. This means:

- `defectsOf()` validation (≥3 meals/day, every meal has a name and non-empty ingredients) needs **zero changes** — a ready-meal "ingredient" satisfies it.
- `ShoppingListService.aggregate`/`deriveFromMealPlan` need **zero changes** — they aggregate whatever `PlannedIngredient` list they're given, and ready-meal products aggregate the same way raw ingredients do (same product ordered on multiple days → one line, summed quantity).
- Calendar rendering needs **zero changes** — it already shows `PlannedMeal.name()` per day; for a ready-meals user that name is already "Обід: Салат Цезар готовий" per the spec's own example, because that's literally what the prompt asks Claude to put in `PlannedMeal.name()`.

`MealPlan` (or the call into `ShoppingListService.deriveFromMealPlan`) carries which path produced it, so the derived `ShoppingListItem` rows get `source_type = READY_MEAL_DIRECT` vs `RECIPE_DERIVED`. Simplest carrier: add a transient/derived `boolean readyMealDirect` parameter to `deriveFromMealPlan(UUID mealPlanId, ShoppingListSourceType sourceType)` — `MealPlanHandoffService` (or wherever this call currently happens) passes through what `MealPlanService.generate()` decided.

`MealPlanService.describe()` also gets extended with the structured adult/children breakdown (male/female adult counts, per-child age bracket) instead of only the flat `householdSize`/`kidsAges` it reads today — this is the mechanism behind the "household composition measurably affects portion sizes" acceptance criterion. Profiles that only have the legacy flat fields (manual-fallback chain, or pre-#20 profiles) keep getting the same text they get today.

### 6. List lifecycle: soft-archive, category, manual CRUD

**Status transitions.** `ShoppingListService.deriveFromMealPlan` and `keepOnly`/`createAdHocList` currently hard-delete the previous rows (`deleteByMealPlanId`, `deleteByUserIdAndIdNotIn`, explicit `deleteAll` in `ShoppingListBuilderService.buildAndShow`). These become UPDATE-to-`ARCHIVED` instead of DELETE:

- `shoppingListItemRepository` gets `archiveActiveByMealPlanId(UUID mealPlanId)` and `archiveActiveByUserId(UUID userId)` (bulk `UPDATE ... SET status = 'ARCHIVED' WHERE status = 'ACTIVE' AND ...`), replacing the delete calls at each of those three call sites.
- New rows are always inserted `status = ACTIVE`.
- Order confirmation (`CartConfirmationService`, existing task-09/10 code) gets one added line: on successful order placement, the ordered items' status flips `ACTIVE → ORDERED`. This is the only touch to that flow, matching "keep compatible with the new status field."
- Every read that means "the list on screen" (`ShoppingListBuilderService.currentItems`, `ShoppingListMessageService` rendering, the new manual-edit lookups) filters `status = ACTIVE`. `ShoppingListItemRepository.findByUserId` either gets a `status` parameter or a new `findByUserIdAndStatus` method — existing callers pass `ACTIVE` explicitly rather than relying on an implicit default, so a future status value doesn't silently leak into "the current list."

**Categorization.** `PlannedIngredient` and `ShoppingListDraft`'s item record both get a `category` component; the meal-plan and shopping-list system prompts ask Claude to fill it (a short fixed vocabulary given in the prompt: "Молочні продукти", "М'ясо і птиця", "Овочі і фрукти", "Крупи і бакалія", "Інше", etc.). `ShoppingListItemMapper.toItem` falls back to a new `CategoryKeywordFallbackService` (simple keyword→category lookup over the item name) when Claude leaves `category` blank — this is arithmetic, not a model call, same spirit as `ShoppingListService.aggregate`. `ShoppingListMessageService.listText` groups the rendered items by `category` (fallback bucket "Інше" for anything still uncategorized) instead of the current flat list.

**Manual edit — structurally distinct from the AI path.** `ShoppingListService` (already has no `ClaudeApiClient` dependency, and must never gain one) gets three new methods:

```java
public ShoppingListItem addItem(UUID userId, String name, BigDecimal quantity, String unit, String category)
public void removeItem(UUID userId, UUID itemId)
public ShoppingListItem updateQuantity(UUID userId, UUID itemId, BigDecimal newQuantity)
```

Plain repository CRUD, no model call, each scoped to the caller's own `ACTIVE` items (a foreign item id or a non-`ACTIVE` item id is a no-op / 404, not a silent cross-user edit). A unit test constructs `ShoppingListService` with a mock `ClaudeApiClient`-bearing collaborator graph and asserts `verifyNoInteractions` across all three — this is the enforcement the spec asks for, backed by the fact that the class's constructor simply has no such dependency to call.

**UI.** `ShoppingListMessageService.listText`/`listButtons` change from one flat message to one message per category (Telegram inline keyboards are message-scoped) — each item line gets its own keyboard row with `−`/`+`/`✕` buttons, callback data `sli:dec:<itemId>`, `sli:inc:<itemId>`, `sli:del:<itemId>`. `ShoppingListBuilderService.handleTap` (or a new small handler next to it) routes these three prefixes to `ShoppingListService.updateQuantity`/`removeItem`, then re-renders. The existing free-text "Редагувати" button is unchanged and still routes through `buildAndShow` → Claude — it's for "change the whole list based on what I say," not single-item edits, and stays the AI path on purpose.

### 7. Out-of-scope confirmation

Reorder/checkin (`ReorderService`, `CheckinFlowService`, etc.) are not touched beyond whatever the `status`/`source_type` column additions require to keep compiling (e.g. if any of them constructs a `ShoppingListItem` directly, it needs a `status` default). No delta-display work — that's #21.

## Testing plan

- `OnboardingFlowIntegrationTest`: new cases for a `WebAppData` submission at `AWAITING_WEBAPP_FORM` (happy path, malformed JSON), and for the `onb:manual` fallback still reaching the same end state as before.
- New test (integration or focused unit) generating two plans from profiles differing only in household composition (e.g. 2 adults vs 2 adults + child `AGE_0_3`) and asserting the prompt text sent to `ClaudeApiClient` differs in the household-description section — this is what acceptance criteria's "measurably affects generated portion sizes" is checkable against without asserting on live model output.
- `MealPlanServiceTest` (or equivalent): `COOKS_BATCH` picks the recipe prompt resource, `READY_MEALS_ONLY` picks the ready-meals one.
- `ShoppingListServiceTest`: `addItem`/`removeItem`/`updateQuantity`, and a view/list call, against a mocked `ClaudeApiClient` collaborator — assert zero interactions.
- `ShoppingListMessageServiceTest` (or a rendering test): items with mixed categories render grouped, not flat.

## Acceptance criteria mapping

- ✅ WebApp form + working fallback → §1, §2.
- ✅ `READY_MEALS_ONLY` → recipe-free plan/list → §5.
- ✅ `COOKS_DAILY`/`COOKS_BATCH` → unchanged flow → §5 (default prompt selection).
- ✅ Zero Claude calls on view/manual edit → §6 (structural: no `ClaudeApiClient` in `ShoppingListService`) + test.
- ✅ Categorized "Список" view → §6.
- ✅ No recipe/day info in List view → unchanged (`ShoppingListMessageService` never read `MealPlan`/recipe fields; this was already true and stays true).
- ✅ Household composition affects generation → §1 (structured fields) + §5 (`MealPlanService.describe()` extended with adult/children breakdown) + test.
