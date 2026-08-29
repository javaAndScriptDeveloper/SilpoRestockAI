# Weekly meal plan generation service (Claude)

Notion task `07. Weekly meal plan generation service (Claude)` (Phase `3. Meal Planning`, Must have, size M,
depends on `04`, `05` — both done).

## Context

Product brief user flow #1, step 3: *агент генерує меню на тиждень → розгортає в список покупок*. This task
is the first half of that arrow. It is also the first time the Claude client (task 04) is used for a real
business purpose rather than a schema-normalisation call.

Task 06 ends by publishing `OnboardingCompletedEvent(userId)` and telling the user "Готую перший план на
тиждень." Nothing listens. This task makes that sentence true.

## Decisions

### Claude is not asked for the week's start date

The Notion task's example JSON carries `weekStartDate`. It is removed from the response shape. The model does
not know today's date, cannot know the user's timezone, and a date it invents would silently disagree with
the `meal_plan.week_start_date` column that the rest of the system reads. The service computes the date —
the upcoming Monday in `Europe/Kyiv`, today when today is Monday — from an injected `Clock`, which also makes
the tests deterministic.

`plan_json` therefore stores `{"days":[...]}` and the week is the column.

### The structured-output records live in `model`, not `dto`

The task text says `dto`. The repository's own convention (`CLAUDE.md`) reserves `dto` for REST
`request`/`response` models and puts "records for Claude structured output" in `model`. `SilpoProfileSnapshot`
from task 06 is already there. Following the task text here would split one kind of type across two packages.

### Content validation is ours; transport retry is the client's

`ClaudeApiClientImpl` already retries on 429 and 5xx and opens a circuit breaker. None of that catches the
failure this task cares about: a well-formed answer with six days in it, or a Tuesday with one meal. So the
service validates the deserialised plan — 7 distinct days `MONDAY`..`SUNDAY`, at least 3 meals per day, every
meal named, every meal carrying at least one named ingredient — and on failure retries **once**, appending
what was actually wrong to the prompt ("у відповіді немає дня SATURDAY"). A second failure raises
`MealPlanGenerationException`; a broken plan is never persisted.

Naming the defect rather than resending the same prompt is the difference between a retry and a coin flip.

### Regeneration inserts; nothing is ever updated

`regenerateWithAdjustment(userId, instruction)` writes a new `meal_plan` row with the same
`week_start_date`. Keeping history is what makes "покажи дифф" (brief flow #6, step 4) possible later.

The consequence is that `MealPlanRepository.findByUserIdAndWeekStartDate` — which returns `Optional` — starts
throwing `NonUniqueResultException` the moment anyone regenerates. It is replaced by
`findFirstByUserIdAndWeekStartDateOrderByCreatedAtDesc`: the current plan for a week is the newest row for
that week.

### The hand-off listener is asynchronous

`MealPlanHandoffService` listens for `OnboardingCompletedEvent`, generates, persists, and sends the user a
short summary. It must be `@Async`: the event is published on the Telegram webhook thread, and generation
takes tens of seconds against the real API. Telegram re-delivers any webhook it does not get a prompt answer
for, so a synchronous listener would produce duplicate updates and duplicate plans.

A failure inside the listener is caught and turned into one plain Ukrainian sentence to the user. An async
listener that throws would otherwise fail into a log line nobody reads.

### The prompt is a resource file

`src/main/resources/prompts/meal-plan-system.txt`, read once at construction through an injected `Resource`.
Iterating on wording is the main activity of a feature like this, and a Java string literal makes every
wording change a recompile. It also keeps the Ukrainian copy out of the diff of logic changes.

The profile goes in the *user* message, not the system prompt: the system prompt is the same for every user
and would otherwise defeat prompt caching later.

## Design

```
OnboardingCompletedEvent (task 06)
        │  @Async @EventListener
        ▼
service/MealPlanHandoffService
        ├── service/MealPlanService.generateWeeklyPlan(userId)
        │       ├── repository/UserProfileRepository        the constraints
        │       ├── prompts/meal-plan-system.txt            the rules
        │       ├── ClaudeApiClient.completeStructured → WeeklyMealPlan
        │       ├── validate → retry once with the defect named
        │       └── repository/MealPlanRepository           INSERT, always
        └── service/telegram/TelegramOutboundService        one-line summary
```

### The response shape

| Record | Fields |
|---|---|
| `WeeklyMealPlan` | `List<PlannedDay> days` |
| `PlannedDay` | `DayOfWeek day`, `List<PlannedMeal> meals` |
| `PlannedMeal` | `MealType type`, `String name`, `List<PlannedIngredient> ingredients` |
| `PlannedIngredient` | `String name`, `BigDecimal quantity`, `String unit` |
| `MealType` | `BREAKFAST`, `LUNCH`, `DINNER`, `SNACK` |

Ingredient quantities are `BigDecimal` because task 08 turns them into a shopping list and then into an
order: money and quantities that reach Silpo must not have been through a `double`.

### What the user prompt carries

Household size, kids' ages, dietary restrictions, disliked foods, the weekly budget as rough guidance rather
than a constraint to optimise, `onlyUaProducer` when set, and `special_mode` when it is not `NONE` — the
medical, mass-gain and blackout modes from task 05 each change what a plan may contain. A user without a
`user_profile` row raises `ApplicationException(PRECONDITION_REQUIRED)` rather than generating a plan for a
household nobody described.

### New files

| File | Holds |
|---|---|
| `service/MealPlanService` | `generateWeeklyPlan`, `regenerateWithAdjustment`, validation, retry |
| `service/MealPlanHandoffService` | the async listener and the user-facing summary |
| `model/WeeklyMealPlan`, `PlannedDay`, `PlannedMeal`, `PlannedIngredient`, `MealType` | the response shape |
| `exception/MealPlanGenerationException` | 502, raised after the retry also fails |
| `resources/prompts/meal-plan-system.txt` | the system prompt, Ukrainian |

### Modified

`repository/MealPlanRepository` (the week finder above), `config/BaseConfig` (`@EnableAsync`),
`integration/SchemaRoundTripIntegrationTest` (one assertion moves to the new finder).

No schema change: `meal_plan` already exists, `plan_json` is JSONB, and nothing about this design needs a
column.

## Testing

`MealPlanIntegrationTest` — Testcontainers PostgreSQL and `StubAnthropicServer`, the pattern tasks 04 and 06
use. The stub returns whatever JSON the test scripts, which is what makes the assertions deterministic.

| Test | Asserts |
|---|---|
| happy path | a `meal_plan` row with 7 days × 3 meals, `week_start_date` = the upcoming Monday |
| the profile reaches the prompt | a vegetarian profile with dislikes appears in `CLAUDE.requests()` |
| six days back | one retry, the retry prompt names the missing day, the good second answer is persisted |
| bad twice | `MealPlanGenerationException`, `meal_plan` still empty |
| regeneration | a second row for the same week; the first is untouched; the adjustment text reached Claude |
| no profile | `ApplicationException`, no Claude call |
| hand-off | `OnboardingCompletedEvent` produces a plan and one Telegram message |

The Notion criterion "dietary restrictions are respected" cannot be proven against a stub — a canned answer
proves nothing about the model. What is provable, and what the test asserts, is that the restrictions reached
the prompt. The design doc records this rather than the test pretending otherwise.

## Out of scope

Turning a plan into a shopping list is task 08; ordering is 09/10. No Telegram command to request a
regeneration — flows #6 and #10 own that conversation, this task only provides the method they will call. No
calorie or macro arithmetic: the model is asked for a plan under an instruction, not audited against one.
