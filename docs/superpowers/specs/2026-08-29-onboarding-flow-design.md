# Onboarding conversation flow + MCP profile enrichment

Notion task `06. Onboarding conversation flow + MCP profile enrichment` (Phase `2. Onboarding`, Must have,
size L, depends on `02`, `03`, `05` — all done).

## Context

The first feature a real person touches. Product brief user flow #1, stage 0. It joins three layers that
already exist: the Telegram webhook and router (task 03), the Silpo MCP client and OAuth (task 02), and the
schema (task 05).

The brief's UX principle governs the whole design: *manual form-filling is bad UX for this audience — people
are paying precisely so they do not have to spend the time*. So the flow asks only for what it could not
find out on its own.

## The gap in the task text

Task 06 step 1 says to call `silpo_get_my_family`, `silpo_get_my_food_restrictions`,
`silpo_get_my_online_orders` and `silpo_get_my_favorites` and pre-fill the profile. A brand-new Telegram user
has never connected a Silpo account, so there is no OAuth token and every one of those calls would answer
401. The flow therefore needs a connect step the task does not mention, placed between the welcome and the
enrichment.

## Decisions

### An explicit connect step, with a fallback that reaches the same place

The welcome message carries a button that opens `SilpoAuthService.buildAuthorizationUrl(userId)` and a
second button to skip. Connected users get enriched; everyone else is asked directly. Both paths end at a
saved `user_profile`, so nothing downstream needs to know which one ran.

Attempting MCP first and reacting to the failure would work, but a new user would watch a silent failure and
never learn that connecting was an option — which is the one thing that makes the rest of the product good.

### Claude normalises the MCP output

No real Silpo guest account exists, so the exact JSON those four tools return is unknown. Rather than guess
key names and fail silently when the guess is wrong, the raw tool output goes to
`ClaudeApiClient.completeStructured(..., SilpoProfileSnapshot.class)`. That is what "MCP profile enrichment"
means, it survives a schema nobody here has seen, and task 04 built the client for exactly this. The cost is
one LLM call per onboarding.

### Enrichment never throws into the flow

Each of the four tool calls is guarded independently: a 403 on `silpo_get_my_favorites` must not discard the
family data already retrieved. Any failure — `SilpoMcpException`, `ClaudeApiException`, an empty result —
yields an empty snapshot. A guest with no order history and a guest who declined to connect take the same
path, so there is one fallback to maintain rather than two.

### The hand-off to task 07 is a Spring application event

`OnboardingCompletedEvent(UUID userId)` is published when the profile is saved. No listener exists yet.
Inventing a `MealPlanService` interface for task 07 to implement would be guessing at someone else's design;
an event lets task 07 choose its own shape.

## Design

```
POST /telegram/webhook
        │
service/telegram/TelegramRoutingService
        │  resolves chat → user, dispatches on conversation state
        ▼
service/onboarding/OnboardingFlowService          state machine over conversation_state
        ├── service/UserAccountService             findOrCreate(chatId)
        ├── service/onboarding/ProfileEnrichmentService
        │       ├── SilpoAuthService.isConnected
        │       ├── SilpoMcpClient.callTool × 4     each independently guarded
        │       └── ClaudeApiClient.completeStructured → SilpoProfileSnapshot
        ├── service/ConversationStateService        step + partial profile
        ├── repository/UserProfileRepository        final persistence
        └── service/telegram/TelegramOutboundService every user-visible message
```

### Steps

`current_step` holds an `OnboardingStep` name; `context_json` accumulates the partial profile.

| Step | Sends | Advances on |
|---|---|---|
| `AWAITING_CONNECT` | welcome + `[Під'єднати Сільпо]` (URL) + `[Пропустити]` | either button |
| `CONFIRM_PROFILE` | what MCP found + `[Все вірно]` + `[Виправлю]` | either button |
| `ASK_HOUSEHOLD` | how many people eat at home | a text reply |
| `ASK_RESTRICTIONS` | allergies and diet restrictions | a text reply |
| `ASK_DISLIKES` | what nobody in the house will eat | a text reply |
| `ASK_BUDGET` | weekly budget | a text reply |
| `DONE` | "готую перший план" | — |

`CONFIRM_PROFILE` is reached only when enrichment produced something. `Виправлю` routes into the same
question chain with the detected values as defaults, which is how "the user can correct any auto-detected
field" is satisfied without a separate editing interface.

`ASK_BUDGET` always runs: MCP cannot know what someone intends to spend. The other questions are skipped
when enrichment already answered them.

Free-text answers are parsed leniently — a household size accepts `4` or `нас четверо`; a budget accepts
`2500` or `2500 грн`. Anything unparseable re-asks rather than storing nonsense.

### Callback data

`onb:connected`, `onb:skip`, `onb:confirm`, `onb:correct`. Prefixed so task 10's cart callbacks can share the
channel without collision.

### One extension to task 03's outbound API

`TelegramButton` currently carries only `callbackData`. The connect button must open a URL, which Telegram
supports natively through `InlineKeyboardButton.url`. `TelegramButton` gains two factories,
`TelegramButton.callback(label, data)` and `TelegramButton.link(label, url)`, and
`TelegramOutboundService` sets whichever is present. A bare link in the message body would work but reads
worse on the single screen that decides whether the product has any data to work with.

### New files

| File | Holds |
|---|---|
| `service/UserAccountService` | `findOrCreate(long telegramChatId) -> User` |
| `service/onboarding/OnboardingFlowService` | the state machine |
| `service/onboarding/ProfileEnrichmentService` | MCP calls and Claude normalisation |
| `model/OnboardingStep` | the enum above |
| `model/SilpoProfileSnapshot` | `(Integer householdSize, Boolean hasKids, List<Integer> kidsAges, List<String> dietaryRestrictions, List<String> frequentItems)` |
| `model/OnboardingCompletedEvent` | `(UUID userId)` |

### Modified

`model/TelegramButton` (link variant), `service/telegram/TelegramOutboundService` (honour it),
`service/telegram/TelegramRoutingService` (dispatch to onboarding, echo removed).

### Copy

Ukrainian, direct, no filler, matching the brief's tone. Nothing that reads as generated text.

## Testing

`OnboardingFlowIntegrationTest` drives real webhook `POST`s through `MockMvc` against three stubs that
already exist in the repository: `StubTelegramServer`, `StubMcpServer`, `StubAnthropicServer`. A connected
user is simulated by inserting an encrypted `mcp_oauth_token` row, because `isConnected` reads the database.

| Test | Asserts |
|---|---|
| happy path, connected | four separate webhook calls end with `user_profile` persisted from MCP-derived values |
| fallback, not connected | skip → asked everything → profile persisted |
| MCP fails mid-enrichment | degrades to asking; no exception reaches the webhook |
| resume | message 1, assert the state row, message 2 later → continues from the saved step, does not restart |
| correction | `Виправлю` overwrites a detected field |
| already onboarded | a later message gets the placeholder reply, not a second onboarding |

Task 03's echo assertions in `TelegramWebhookIntegrationTest` are rewritten: text messages now enter
onboarding. Task 03 said the echo would be deleted when this task landed, and this is that.

## Out of scope

Meal plan generation is task 07. This change ends at "profile saved, event published". No check-in flow, no
cart. No editing of a completed profile — a user who wants to change something later is task 06's successor,
not task 06.
