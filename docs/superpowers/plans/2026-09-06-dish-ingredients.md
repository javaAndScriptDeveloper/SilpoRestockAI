# Order the ingredients for a dish (task 36) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** «Замов усе для карбонари» — or a photo of a plated dish with that caption — produces a small AD_HOC cart of the dish's ingredients, resolved through the ordinary catalog search, placed through the same `scheduled_ad_hoc_task` mechanism as every other one-off purchase, never touching the baseline.

**Architecture:** `scheduled_ad_hoc_task` gains a `kind` (`SNACK_THEME`, the existing default; `DISH_INGREDIENTS`). `AdHocScheduleService.fire(task)` dispatches by kind, and is what both the sweep and the new immediate path call — one mechanism, one row per request, visible in «Заплановані» as FIRED. `DishIngredientsService` asks Claude for a single dish's ingredient list (structured, scoped to the household's size), turns it into unresolved `ShoppingListItem`s and hands them to `CartConfirmationService.present(..., AD_HOC)` — exactly task 09's name-search resolution with partial-resolution honesty, not task 22's search-first override. `DishRequestService` owns the conversation: a dish name in the sentence fires immediately; no name asks for one or a photo; a photo (with a caption the router classifies as this intent) is identified by vision and confirmed before anything is generated.

**Tech Stack:** Liquibase `026`, Claude structured output + vision, Telegram inline buttons, MockMvc integration test with cart stubs.

**Spec:** Notion task 36 (`3d27227d-ef1c-81bc-acf8-f44c0dcc4ae3`).

## Global Constraints

- Same scheduling concept as task 31 — a row in `scheduled_ad_hoc_task`, never a bypass. Deviation, documented: the row is fired immediately after creation through the same `fire` the sweep uses, because "run now" through a 15-minute cron is a wait nobody asked for (see `OVERNIGHT_QUESTIONS.md`).
- Ingredient resolution reuses `CartBuildingService.buildCart` (task 09) — no product ids invented, unresolved lines surfaced.
- `OrderType.AD_HOC` → the baseline is never written.
- No recipe steps in any message.

---

### Task 1: Kind on the scheduled task, dispatch by kind, immediate fire

**Files:** `db/changelog/changes/026-scheduled-ad-hoc-task-kind.yaml`, `model/ScheduledAdHocTaskKind.java`, `entity/ScheduledAdHocTask.java`, `service/AdHocScheduleService.java` (`fire`, `scheduleDishIngredients`), `service/ScheduledTaskManagementService.java` (render «інгредієнти для «X»»).

### Task 2: Generation and cart

**Files:** `model/DishIngredients.java`, `prompts/dish-ingredients-system.txt`, `prompts/dish-identify-system.txt`, `service/DishIngredientsService.java` (`orderIngredients(User, String dish)`, `Optional<String> identifyDish(byte[])`).

### Task 3: Conversation and routing

**Files:** `model/ConversationFlow.java` (+`DISH_CONFIRM`), `service/DishRequestService.java`, `model/TelegramIncomingUpdate.java` (`Photo.caption`), `service/telegram/TelegramRoutingService.java` (caption → `IntentRouterService.routePhoto`; flow dispatch), `service/IntentRouterService.java` (+`DISH_INGREDIENTS_ORDER`, `routePhoto`, help line), `prompts/intent-router-system.txt`.

### Task 4: Tests, docs, Notion

- `integration/DishIngredientsIntegrationTest.java`: (1) text with a dish → task row `DISH_INGREDIENTS`/`FIRED`, Claude ingredients → cart draft `AD_HOC`, «карбонара» in the message, `silpo_find_products_batch` called (task 09 path), baseline untouched; (2) photo + caption → classification, vision identify → «Схоже на «карбонара». …» buttons → yes → cart; (3) no dish name → asks; a typed name proceeds; (4) «Ні» → asks for the name → typed name proceeds.
- RUNBOOK «Task 36»; `OVERNIGHT_QUESTIONS.md` (immediate fire; caption-gated photo path; why the fridge-photo default stays); Notion → In review.
