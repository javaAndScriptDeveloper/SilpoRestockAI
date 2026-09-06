# Seed the list from a real past Silpo order (task 35) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** «Зроби список як минулого разу» → the bot lists the household's recent Silpo orders, they pick one, and its real line items (already-resolved product ids, quantities, prices) become the live shopping list — no AI generation, no catalog search — which then goes through the ordinary Замовити → cart → confirm path and becomes the baseline.

**Architecture:** One new `PastOrderSeedService` and one new conversation flow `PAST_ORDER_PICK`. `offer` calls `silpo_get_my_online_orders` and `silpo_get_my_offline_orders` (each tolerated independently, as `ProfileEnrichmentService` does), parses them with the same key-guessing `McpResponses` helpers the rest of the app uses, stores up to five summaries in `conversation_state.context_json`, and shows them as buttons. A tap turns the picked order's lines into `PlannedIngredient`s carrying `productId` and `price`, stores them via `ShoppingListService.createAdHocList(..., PAST_ORDER)`, and hands them to `ShoppingListBuilderService.present` — from there nothing is new: `CartBuildingService.resolveProducts` already skips the search for lines that carry a product id (task 22), and the confirmation stores the baseline (task 10). Chat-first entry (`PAST_ORDER_SEED` intent), not an extra onboarding step — see the decision log. **Case B** (a receipt from another shop) already exists as the list builder's «фото чека» path; only its copy is made honest about precision.

**Tech Stack:** Spring service, Telegram inline buttons, MCP stub in tests.

**Spec:** Notion task 35 (`3d27227d-ef1c-81f5-b274-da8b2398e639`).

## Global Constraints

- Zero `silpo_find_products_batch` calls on this path — every seeded line carries `silpoProductId`.
- Always show the list and let the person pick; never guess which order.
- Unknown response shapes are handled with the `McpResponses` key arrays and reported honestly when empty; the live check is what confirms the real shape.

---

### Task 1: Service, flow, intent

**Files:**
- Create: `service/PastOrderSeedService.java`, `model/PastOrderSummary.java`, `model/PastOrderLine.java`
- Modify: `model/ConversationFlow.java` (+`PAST_ORDER_PICK`), `model/ShoppingListSourceType.java` (+`PAST_ORDER`), `utils/McpResponses.java` (+`ORDERS`, `ORDER_ID`, `ORDER_DATE`), `service/ShoppingListService.java` (`createAdHocList` overload with a source type), `service/IntentRouterService.java` (+`PAST_ORDER_SEED`, help line), `prompts/intent-router-system.txt`, `service/telegram/TelegramRoutingService.java` (flow dispatch), `service/telegram/ShoppingListMessageService.java` (receipt copy)
- Test: `integration/PastOrderSeedIntegrationTest.java` — (1) intent → buttons from stubbed online+offline orders, newest first, (2) tap → list with product ids and prices, no `silpo_find_products_batch` call, source `PAST_ORDER`, flow `LIST_BUILDING`, (3) no orders → honest message, flow `NONE`, (4) picked order without lines → honest message.

### Task 2: Docs, gate, Notion

- RUNBOOK «Task 35»: the live check — pick a real order, compare items 1:1, and record the real JSON shape if the buttons come out wrong.
- `OVERNIGHT_QUESTIONS.md`: chat-first entry vs onboarding step; Case B already exists; unknown shapes.
- Notion task 35 → In review (the 1:1 comparison against a real account is the acceptance criterion).
