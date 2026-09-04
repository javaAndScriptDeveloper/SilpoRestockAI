# Chat-first intent router (task 31) — design

**Status:** scoped down from the full Notion task for one overnight autonomous session (see
`docs/OVERNIGHT_QUESTIONS.md` → "Task 31: additive rollout, not a destructive rewrite of the router" for
the full reasoning). This spec covers the scope actually being built tonight; the deferred pieces are
listed at the end.

## Problem

Комора is pitched as an agent, but is controlled through a growing pile of slash commands and buttons —
every new capability needs a new button, which doesn't scale and undercuts the "just tell it what you
want" pitch. The product decision (already made, not re-litigated here) is a chat-first intent router:
free text is classified into a fixed intent taxonomy and dispatched to the backend service that already
owns that capability.

## Scope for tonight

**In scope:**
1. `IntentRouterService` — classifies free text into one of: `AD_HOC_SCHEDULED_PURCHASE`,
   `SPECIAL_MODE_MEDICAL_GASTRITIS`, `SPECIAL_MODE_LEANER`, `SPECIAL_MODE_MASS_GAIN`,
   `FILTER_UA_PRODUCER_ONLY`, `LIST_VIEW`, `HELP`, `UNKNOWN`, via one `ClaudeApiClient.completeStructured`
   call, and dispatches to the already-existing owning service. `LIST_MODIFY` is folded into `LIST_VIEW`'s
   dispatch (`ShoppingListBuilderService.askForInput`) — the existing `LIST_BUILDING` flow already handles
   free-text edits once inside it; no acceptance criterion tests a cold-start single-message edit.
2. Wired in **additively**: it becomes the new fallback in `TelegramRoutingService.handle()`, replacing
   the current single-purpose `detectGastritisIntent` check and the generic "profile already exists"
   message. Every existing slash-command branch (`/blackout`, `/masgain`, `/uaonly`, `/normal`,
   `/reorder`, `/list`, `/voice`, `/calendar`) stays exactly as it is — untouched, still tested by their
   own existing integration tests.
3. `MainMenuKeyboard` swaps to the three-button target: Список / Анкета / Інструкція. The underlying slash
   commands for the retired buttons keep working if typed — only the visible keyboard changes.
4. "Інструкція" — a static help message, example phrasings mapped to plain-language descriptions,
   covering the intents above.
5. Scheduled ad-hoc purchases: a new `scheduled_ad_hoc_task` table (`id`, `user_id`, `trigger_at`,
   `theme_description`, `status`, `created_at`), a small `AdHocScheduleService` (schedule / sweep-due,
   mirroring `SpecialModeService`/`SpecialModeScheduler`'s existing pattern exactly), and an
   `AdHocScheduleScheduler` `@Scheduled` job. `IntentRouterService` writes a row instead of calling
   `AdHocOrderService` directly for this intent; the sweep calls task 24's existing
   `AdHocOrderService.buildAdHocOrder(user, theme, triggerAt)` when a row comes due.
6. `SPECIAL_MODE_MASS_GAIN` dispatch also sends one extra short message pointing at sports-nutrition
   products, before handing off to `SpecialModeService.startMassGainSetup` — satisfies the "cross-sell
   surfaced only for the protein/mass-gain direction" criterion without touching #25's own code (task 31's
   own out-of-scope note forbids changing #07/#08/#24/#25's business logic).

**Deferred (will not be built tonight — logged, not silently dropped):**
- "Анкета" reopen + explicit "regenerate list?" confirmation flow (acceptance criterion 7). This is the
  most UI-flow-heavy, lowest-reuse piece, and nothing else in the task depends on it — the safest thing to
  leave for a session with a human awake to review the new conversation flow live.
- Full deletion of the now-partially-redundant slash-command branches (acceptance criterion 6's stronger
  reading — see the additive-rollout decision above).

## Classification

One `ClaudeApiClient.completeStructured` call per free-text message (only reached when no
`conversation_state` flow is active and no slash command matched), system prompt enumerating the intent
taxonomy in Ukrainian with examples, same idiom as `SpecialModeService`'s existing `GastritisIntent`
classifier. Response shape:

```java
private record ClassifiedIntent(
    String intent,              // exact enum constant name; parsed defensively, unknown -> UNKNOWN
    double confidence,
    String themeDescription,    // AD_HOC_SCHEDULED_PURCHASE only
    String targetDateTimeIso    // AD_HOC_SCHEDULED_PURCHASE only; ISO-8601, null if not extractable
) {}
```

`intent` is a `String`, not a Java enum, deliberately: no structured-output call in this codebase has ever
used a Claude-schema-derived enum field, and defensively parsing a string (`IntentType.valueOf(...)`,
falling back to `UNKNOWN` on any mismatch) costs nothing and removes a completely untested risk.

Confidence below `0.6` (matching the `0.7` threshold `SpecialModeService.detectGastritisIntent` already
uses, loosened slightly since this covers more intents with more classification surface) or an `UNKNOWN`
intent produces a clarifying question rather than silent inaction or a guess — same principle as task 12's
check-in parsing.

## Dispatch table

| Intent | Handler |
|---|---|
| `AD_HOC_SCHEDULED_PURCHASE` | `AdHocScheduleService.schedule(user, themeDescription, triggerAt)` |
| `SPECIAL_MODE_MEDICAL_GASTRITIS` | `SpecialModeService.triggerGastritis(user)` |
| `SPECIAL_MODE_LEANER` | `MealPlanService.regenerateWithAdjustment(userId, "менш калорійний")` → derive → `ShoppingListBuilderService.present` |
| `SPECIAL_MODE_MASS_GAIN` | cross-sell message, then `SpecialModeService.startMassGainSetup(user)` |
| `FILTER_UA_PRODUCER_ONLY` | `SpecialModeService.toggleUaOnly(user)` |
| `LIST_VIEW` | `ShoppingListBuilderService.askForInput(user)` |
| `HELP` | static message |
| `UNKNOWN` / low confidence | clarifying question |

## Testing

Integration test drives real webhook text through `TelegramRoutingService`, with `StubAnthropicServer`
scripted to return each `ClassifiedIntent` shape in turn — one test per dispatch row, plus a low-confidence
clarifying-question test, plus a schedule→sweep→cart-built end-to-end test for the ad-hoc case (mirroring
`SpecialModeIntegrationTest`'s own `sweepTransitionsAcuteToDietTable5WhenTheAcuteDurationHasPassed`
fast-forwarded-clock pattern). `MainMenuKeyboard`'s new three-button shape gets its own small assertion.
Full existing suite re-run after this lands, to confirm the additive wiring broke nothing.
