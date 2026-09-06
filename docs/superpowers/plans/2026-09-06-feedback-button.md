# Feedback button (task 47) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fifth persistent-menu button «💬 Фідбек» that captures one free-text message raw into a `feedback` table and puts the chat back exactly where it was.

**Architecture:** A new `ConversationFlow.FEEDBACK` whose `context_json` is a snapshot of the previous state (flow, step, context). `FeedbackService.prompt` takes the snapshot and asks; `handle` stores the next text and restores the snapshot; a persistent-menu tap while the prompt is open abandons it (restore, no row) so a tester who changes their mind cannot have their next list edit swallowed as feedback. Both the trigger and the flow are checked *before* the onboarding gate in `TelegramRoutingService`, so feedback works from the very first message. No AI, no categorisation, no admin UI.

**Tech Stack:** Liquibase changeset `024`, JPA entity + Spring Data repository, Spring service, Telegram reply keyboard (2×2 + 1 full-width row), MockMvc integration test with stub servers.

**Spec:** Notion task 47 (`3d37227d-ef1c-81dd-96fe-f38612dd74a9`); menu target in task 29 («п'ять кнопок»).

## Global Constraints

- Telegram SDK types stay in `controller.telegram` / `service.telegram` (ArchUnit).
- `Service` / `Repository` suffixes; constructor injection.
- Nothing held in a field of the flow service — snapshot lives in `conversation_state.context_json`.
- Copy: «ти», short; confirmation is exactly «Дякую, врахуємо.» (spec).
- `feedback.user_id` FK to `users`, nullable per spec (the row must survive a deleted user only if cascade is off — we cascade, as `shopping_list_item` does, since a deleted test user's feedback is noise).

---

### Task 1: Table, entity, repository, service

**Files:**
- Create: `src/main/resources/db/changelog/changes/024-feedback.yaml`
- Create: `src/main/java/com/silporestockai/entity/Feedback.java`
- Create: `src/main/java/com/silporestockai/model/FeedbackSource.java` (`BUTTON`)
- Create: `src/main/java/com/silporestockai/repository/FeedbackRepository.java`
- Create: `src/main/java/com/silporestockai/service/FeedbackService.java`
- Modify: `src/main/java/com/silporestockai/model/ConversationFlow.java` (+ `FEEDBACK`)

**Interfaces:**
- `FeedbackService.prompt(User)`, `FeedbackService.handle(User, TelegramIncomingUpdate)`, `boolean FeedbackService.abandonIfPending(long chatId)`, `Feedback FeedbackService.submit(User, String rawText, FeedbackSource)`; `FeedbackService.CALLBACK_CANCEL = "fb:cancel"`.

- [ ] Steps: write `FeedbackIntegrationTest` (Task 3) first, watch it fail to compile, then implement the five files above, then run `ArchitectureTest` + the new test.

### Task 2: Button and routing

**Files:**
- Modify: `src/main/java/com/silporestockai/service/telegram/MainMenuKeyboard.java` (`FEEDBACK = "💬 Фідбек"`, third row, `isButton(String)`)
- Modify: `src/main/java/com/silporestockai/service/telegram/TelegramRoutingService.java` (trigger + flow before the onboarding gate; menu tap abandons a pending prompt)
- Modify: `src/main/java/com/silporestockai/service/IntentRouterService.java` (help text: one line for the fifth button)
- Modify: `src/test/java/com/silporestockai/integration/TelegramOutboundServiceIntegrationTest.java` (three rows)

### Task 3: Tests

**Files:** `src/test/java/com/silporestockai/integration/FeedbackIntegrationTest.java`

1. Tap «💬 Фідбек» on an idle onboarded chat → prompt with a «Скасувати» button, flow `FEEDBACK`; send text → one row (chat id, user id, raw text, `BUTTON`, `created_at`), «Дякую, врахуємо.», flow `NONE`.
2. Mid-flow (`LIST_BUILDING` / `AWAITING_APPROVAL` / context) → after capture the state is byte-for-byte what it was.
3. Not onboarded (`ONBOARDING` / `AWAITING_CONNECT`): `/feedback` → captured with the user id, state back to onboarding.
4. A persistent-menu tap while the prompt is open → no row, flow no longer `FEEDBACK`.
5. «Скасувати» → no row, previous state restored.

### Task 4: Docs, gate, Notion

- RUNBOOK: menu is five buttons (2×2 + «💬 Фідбек»); a «Task 47» checklist plus the SQL to read feedback.
- `OVERNIGHT_QUESTIONS.md`: snapshot/restore choice, menu-tap-abandons rule, before-the-onboarding-gate placement.
- Notion: task 47 → In review; task 29's five-button criterion now true; demo script step 2 → five buttons.
