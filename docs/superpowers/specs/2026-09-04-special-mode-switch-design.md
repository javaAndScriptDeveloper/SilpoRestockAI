# Special-mode switch commands (gastritis, mass gain, UA-producer-only)

Notion task 25 in "Комора — Development Plan" (Phase 3. Meal Planning, Must have, depends on
05/07/20).

## Context

Комора's `user_profile` schema already anticipates special modes (`special_mode` enum:
`NONE, MEDICAL_GASTRITIS_ACUTE, MEDICAL_DIET_TABLE_5, MASS_GAIN, BLACKOUT`, plus
`only_ua_producer` boolean, plus `MealPlanService.regenerateWithAdjustment(...)`), but nothing in
the chat layer actually sets these fields — the capability exists in the database but is
invisible and unreachable from Telegram.

The gastritis case is the priority: it is step 8 of the "Сценарій демо-запису" (demo recording
script) — currently 🔴 blocked, and one of the strongest agentic-behavior moments for the
hackathon jury ("скажіть боту про хворобу — і він сам підлаштує раціон, а потім сам повернеться
до звичайного — без вашої участі"). It must be fully self-contained end-to-end: trigger → medical
plan → automatic reversion after expiry, with no data loss to the user's normal baseline. Mass
gain and UA-producer-only are explicitly scoped simpler: manual toggles, no auto-expiry.

Pre-check done before this design: tasks 09 (MCP cart flow) and 12 (check-in parsing) were
verified against their Notion acceptance criteria. Both are functionally complete — 292/293 tests
pass; the one failure is a pre-existing, unrelated ArchUnit naming violation on 4 nested classes
(`CartBuildingService$UnitAmount`, `CartBuildingService$UnitKind`,
`OnboardingFlowService$WebAppOnboardingPayload`, `service.telegram.MainMenuKeyboard`), not caused
by tasks 09/12. Each has exactly one open acceptance-criteria item, and both are live-credential
manual smoke tests already documented as runbooks in README ("Building a real Silpo cart",
"Speech to text (voice check-ins)") — not code gaps. They do not block task 25; their Notion
status is left as-is by this change.

## Goal

Give the user real chat-based entry points to switch `special_mode` and `only_ua_producer`, with
the gastritis case fully modeled including automatic two-stage reversion after a configurable
duration, without ever losing the household's normal `BaselineBasket`.

## Design decisions

1. **Gastritis is two-stage**, using both existing enum values: `MEDICAL_GASTRITIS_ACUTE` (short,
   strict) → `MEDICAL_DIET_TABLE_5` (gentler, longer) → `NONE` (normal). Each stage has its own
   configurable duration; the sweep job drives both the ACUTE→DIET_TABLE_5 and the
   DIET_TABLE_5→NONE transition.
2. **No stacking**: if a special mode is already active and the user triggers a different one,
   reject with a message telling them to cancel/finish the current one first — matches the
   Notion task's "Out of scope" line verbatim.
3. **Manual early exit**: add a `/normal` command (+ main-menu button) that cancels any active
   special mode immediately and restores the normal profile, via the same code path the
   auto-expiry sweep uses. Fallback for mass gain/UA-only (no auto-expiry) and for demo recovery.
4. **UA-producer-only**: there is no producer/brand/country field anywhere in this app's MCP
   product data model (`utils/McpResponses`, all test fixtures, all docs) — never observed
   against a live server, confirmed by investigation. Real client-side filtering is not
   implementable today. Approach: when `only_ua_producer = true`, bias the search-query text sent
   to `silpo_find_products_batch` (append "українського виробництва" to each item name) at both
   call sites (`CartBuildingService.resolveProducts`, `ReadyMealCatalogService.findCandidates`),
   relying on Silpo's own search ranking. This is documented as an unverified-against-live-server
   best-effort — the same caveat class as tasks 09/12's live smoke tests — not a guaranteed
   filter.

## Data model changes

New Liquibase changeset `src/main/resources/db/changelog/changes/021-special-mode-expiry.yaml`:
- `user_profile.special_mode_expires_at TIMESTAMP WITH TIME ZONE` (nullable) — when the current
  stage ends.
- `meal_plan.special_mode VARCHAR(64)` (nullable) — which `SpecialMode` (if any) produced this
  plan row, for future UI/debugging use.
- `user_profile.target_weight_kg NUMERIC`, `target_calories INT`, `target_protein_g INT` (all
  nullable) — mass gain's extra parameters. Confirmed via `UserProfile.java` that no such fields
  exist yet.

## Why the baseline is safe by construction

`BaselineBasket` (a dedicated table, one `is_current=true` row per user) is completely decoupled
from `MealPlan`. `ShoppingListBuilderService.order()` only calls
`CartConfirmationService.storeBaseline(...)` for `OrderType.INITIAL`, never `AD_HOC`. As long as
every special-mode order goes through the same `AD_HOC` path `BlackoutModeService` already uses
(never the 1-arg `CartConfirmationService.present(user, items)` overload, which defaults to
`INITIAL`), the baseline is never touched — no snapshot/restore mechanism is needed, just this one
invariant. `shopping_list_item`'s on-screen list *will* get replaced when a special-mode list is
presented (`ShoppingListBuilderService.present()` archives whatever was live, by design); on exit,
the revert path re-derives and re-presents a normal list from a freshly regenerated normal
`MealPlan`, rather than assuming anything survived in `shopping_list_item`.

## New component: `SpecialModeService`

`service/SpecialModeService.java`, following the `BlackoutModeService`/`CheckinPromptService`
conventions (constructor injection, `Clock` injected for testability):

- `triggerGastritis(User user)` — guard: reject if `specialMode != NONE`. Else set
  `specialMode = MEDICAL_GASTRITIS_ACUTE`, `specialModeStartedAt = clock.instant()`,
  `specialModeExpiresAt = started + acuteDuration`. Regenerate via `MealPlanService` with the
  gastritis-acute prompt, derive + present the shopping list, submit any resulting order as
  `AD_HOC`, send confirmation.
- `triggerMassGain(User user, weightKg, targetCalories, targetProteinG)` — same guard; called
  after conversational parameter collection. Sets `specialMode = MASS_GAIN` and the three target
  columns, regenerates with the mass-gain prompt. No expiry set.
- `toggleUaOnly(User user)` — flips `only_ua_producer`, independent of `specialMode`.
- `cancel(User user)` — the `/normal` handler: no-op message if already `NONE`; else clears
  `specialMode`/`specialModeStartedAt`/`specialModeExpiresAt`, regenerates a normal plan,
  re-presents the list, notifies.
- `sweepExpired()` — called by the scheduler; queries `user_profile` where
  `special_mode_expires_at <= now`
  (`UserProfileRepository.findAllWithExpiredSpecialMode(Instant now)`):
  - `MEDICAL_GASTRITIS_ACUTE` → transitions to `MEDICAL_DIET_TABLE_5`, new
    `specialModeExpiresAt = specialModeStartedAt + acuteDuration + diet5Duration`, regenerates
    with the diet-table-5 prompt, notifies ("Гострий період завершено, переходимо до дієтичного
    столу №5 ще на N днів").
  - any other durational mode at expiry → same as `cancel(user)`, with the auto-revert
    notification ("Два тижні дієтичного харчування завершено, повертаємось до звичайного
    раціону").
- `detectGastritisIntent(String text)` — `ClaudeApiClient.completeStructured(...)` classification
  returning `{"isIllnessTrigger": bool, "confidence": ...}` against a small system prompt, used by
  the free-text trigger below.

## Config

```java
@ConfigurationProperties(prefix = "komora.special-mode")
public record SpecialModeProperties(Duration gastritisAcuteDuration, Duration gastritisDiet5Duration, String sweepCron)
```
```yaml
komora:
  special-mode:
    gastritis-acute-duration: ${GASTRITIS_ACUTE_DURATION:3d}
    gastritis-diet5-duration: ${GASTRITIS_DIET5_DURATION:11d}
    sweep-cron: ${SPECIAL_MODE_SWEEP_CRON:0 0 * * * *}
```
Both durations are `Duration`-typed and env-overridable, so the manual test fast-forwards expiry
by setting a short env value (e.g. `GASTRITIS_ACUTE_DURATION=30s`) instead of hardcoding "two
weeks" anywhere. `job/SpecialModeScheduler.java` is a thin `@Component`:
```java
@Scheduled(cron = "${komora.special-mode.sweep-cron}")
public void sweepExpiredSpecialModes() { specialModeService.sweepExpired(); }
```

## Prompt selection in `MealPlanService`

Extends the existing prompt-file-selection mechanism (currently a binary choice between
`meal-plan-system.txt` / `meal-plan-ready-meals-system.txt` based on `cookingTimePreference`) to
branch on `profile.getSpecialMode()` first. New files:
- `resources/prompts/meal-plan-gastritis-acute-system.txt`
- `resources/prompts/meal-plan-gastritis-diet5-system.txt`
- `resources/prompts/meal-plan-mass-gain-system.txt`

When `specialMode != NONE`, the special-mode prompt takes priority over the recipe/ready-meals
split; falls back to today's logic when `NONE`. The existing `describe(...)` user-prompt builder
already appends the `special_mode` line — left as-is, it reinforces intent.

## Command & trigger wiring

`service/telegram/MainMenuKeyboard.java`: add `NORMAL = "↩️ Звичайний режим"`. Mass gain/UA-only
are reached via explicit slash commands (not added to the fixed keyboard, to keep it uncluttered).

`TelegramRoutingService.handle(...)`: new command blocks in the same position/style as
`/blackout`:
- `/masgain` → starts the mass-gain parameter-collection flow.
- `/uaonly` → `specialModeService.toggleUaOnly(user)`.
- `/normal` → `specialModeService.cancel(user)`.

**Gastritis free-text trigger**: added to the existing fallback branch (unmatched text, no active
flow) — calls `specialModeService.detectGastritisIntent(text)`; on a confident positive, calls
`triggerGastritis(user)` instead of the generic "pick an action" message. Keeps existing command
dispatch untouched; only spends a Claude call on genuinely unmatched free text.

## Mass gain parameter collection

Follows `OnboardingFlowService`'s conversation-state convention (CLAUDE.md: no service instance
fields, `conversation_state.context_json` only). Adds `SPECIAL_MODE_SETUP` to `ConversationFlow`.
`/masgain` starts the flow; a small step machine collects weight → calorie target → protein
target (mirroring `OnboardingFlowService`'s step/context pattern), then calls
`SpecialModeService.triggerMassGain(...)` and clears the flow.

## Testing plan

- Unit/service tests for `SpecialModeService`: trigger guards (reject when already active),
  two-stage gastritis transition math with an injected fixed `Clock`, `/normal` cancel path,
  UA-only toggle independence from `specialMode`.
- Integration test (`SpecialModeIntegrationTest`, through `POST /telegram/webhook` with a stub
  Anthropic client, mirroring `CheckinParsingIntegrationTest`'s shape): full gastritis flow —
  trigger → plan changes → advance injected clock past acute duration → sweep →
  diet-table-5 transition → advance past diet5 duration → sweep → reverts to `NONE` → normal plan
  regenerated → `BaselineBasket.isCurrent` row unchanged throughout.
- Assert every special-mode order path passes `OrderType.AD_HOC`, never `INITIAL`.
- ArchUnit: `SpecialModeService`/`SpecialModeScheduler` satisfy existing naming/layer rules.

## Acceptance criteria (from Notion task 25)

- [ ] Saying/triggering "я захворів, гастрит" switches `special_mode`, regenerates the meal plan
      with a medical-diet prompt, and schedules automatic reversion after the configured
      duration.
- [ ] After the special-mode duration expires, the profile automatically reverts to normal and
      the user is notified, without requiring any manual action.
- [ ] Mass gain mode collects the required extra parameters (weight, calorie/protein target)
      before generating a plan, and produces a visibly different (higher-calorie/protein) plan
      than the default.
- [ ] UA-producer-only toggle, once set, is respected by every subsequent product search
      (best-effort search-query bias, per the design decision above — not a guaranteed filter,
      since no producer field exists in the MCP product data yet).
- [ ] The user's normal baseline basket is untouched/preserved during a special mode and is what
      is restored when the mode ends.
- [ ] Manual test covering the full gastritis flow: trigger → medical plan generated →
      (fast-forwarded expiry) → automatic reversion confirmed.

## Out of scope

No support for stacking multiple special modes simultaneously — only one `special_mode` active
at a time.

## Manual verification

1. Trigger gastritis via free text in a real/test Telegram chat ("я захворів, гастрит").
2. Confirm the plan visibly changes to the medical prompt's output and the shopping list updates.
3. Set `GASTRITIS_ACUTE_DURATION`/`GASTRITIS_DIET5_DURATION` to seconds, let the sweep fire (or
   trigger `sweepExpiredSpecialModes()` manually) to confirm ACUTE→DIET_TABLE_5→NONE.
4. Confirm the user is notified at each transition and the final revert message matches spec.
5. Confirm `BaselineBasket.isCurrent` is untouched throughout.
6. Spot-check mass gain (`/masgain` → answer prompts → visibly higher-calorie plan) and `/uaonly`
   (toggle persists, search terms are biased — best-effort).
