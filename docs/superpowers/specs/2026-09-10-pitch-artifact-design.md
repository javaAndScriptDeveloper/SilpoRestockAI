# Pitch artifact: the MCP call sheet (task 55)

## Problem

Two judging criteria — «Якість використання MCP» (breadth and justification of tool usage) and «Агентність
рішення» (a real sequence of tool calls, not generated text) — are the two the pitch cannot prove by talking.
Task 54's Grafana dashboards monitor these numbers over time, which is the wrong shape for a jury: a
dashboard is read for ten seconds and forgotten. What proves the two criteria is a plain, readable sheet,
generated from a real run, that a judge can open from a QR code and read once, carefully.

Task 55 splits that artifact out of #54 deliberately: it is not an operational panel, and it must never be a
live feed of the running application.

Sources read for this design (2026-09-10): the Notion task page, `MetricsService` and `McpCallLogService`
(task 37's instrumentation), `IntentRouterService`, `ObservabilityService`, `InternalMetricsController`, the
`Caddyfile` and `docker-compose.prod.yml` from task 59, and `docs/RUNBOOK.md`.

## Goal

One self-contained HTML page, generated from the tables a real run already fills, reachable at a stable
public URL on the deployed host, containing:

1. **The MCP tool matrix** — every distinct `silpo_*` tool actually called, how many times, how many of those
   failed, and one line saying which flow uses it and why.
2. **The intent distribution** — how many times each intent from task 31's taxonomy actually fired, including
   the intents that never produce an order.

Both numbers come from rows in Postgres written by the running system. Nothing on the page is typed by hand
except the per-tool flow notes, which explain rather than count.

## What already exists, and is reused rather than rebuilt

| Need | Existing mechanism |
|---|---|
| Distinct tool names, call counts, failures | `mcp_tool_call`, one row per `McpToolCalledEvent`, written by `McpCallLogService` (task 37) |
| The «N of 40» denominator | `MetricsService.SILPO_MCP_TOOL_COUNT` |
| A token-guarded internal report endpoint | `InternalMetricsController` + `MetricsProperties` + `X-Metrics-Token` |
| A `make` target that pulls a report off a running app | `make metrics`, `make promotions` |

The artifact adds no second way of counting tool calls. It reads the same table task 37 already writes and
task 54 already gauges.

## What does not exist: the intent distribution

`ObservabilityService.recordIntent(...)` counts three outcomes — `routed`, `unclassified`, `failed` — and
carries no per-intent tag on purpose; `IntentRouterService` says so in a comment, naming this task as the
place where the per-intent breakdown belongs. `customer_order.trigger_intent` (task 75) records the intent
behind an order, but only for the intents that build one: `LIST_VIEW`, `CALENDAR_VIEW`, `WHERE_IS_MY_ORDER`,
`MY_BENEFITS`, `HELP`, `PAST_ORDER_SEED`, `CALENDAR_CONNECT`, `FILTER_UA_PRODUCER_ONLY` and
`SPECIAL_MODE_END` leave no order row at all. A distribution derived from orders would silently claim the
router dispatches nine fewer intents than it does.

So one new evidence table, built the same way task 37's was — an application event and a listener that writes
a row and swallows its own failures.

### `intent_classification`

| Column | Type | Meaning |
|---|---|---|
| `id` | uuid, PK | |
| `intent` | varchar(64), not null | The `IntentType` name for a routed classification; `UNCLASSIFIED` when the model's answer was unknown or below the confidence threshold; `FAILED` when the classification call itself threw |
| `outcome` | varchar(16), not null | `ROUTED`, `UNCLASSIFIED`, `FAILED` — the same three outcomes `recordIntent` already publishes, so the page and the Grafana counter can be reconciled |
| `confidence` | double precision, null | The model's own confidence; null for `FAILED` |
| `user_id` | uuid, null | Whose message it was. Never rendered on the page — it is here so a suspicious count can be traced in the database, the same reason `mcp_tool_call` carries one |
| `classified_at` | timestamptz, not null | |

Changeset `034-intent-classification.yaml`.

**Why `UNCLASSIFIED` and `FAILED` are rows and not omissions.** A distribution that shows only successes
invites the question the jury will actually ask: how often does the router get it wrong? The page answers it
without being asked. `unlessIntents` rejections (the check-in's «not for me» path) are deliberately **not**
recorded — the classification succeeded and the intent fired; who consumed it is not this page's subject.

### The event

`IntentClassifiedEvent(String intent, String outcome, Double confidence, UUID userId, Instant classifiedAt)`
in `model`, published by `IntentRouterService` at the three points that already call `recordIntent`, and
consumed by `IntentLogService` — a direct structural copy of `McpToolCalledEvent` / `McpCallLogService`,
including the swallowed failure: a broken evidence log must never turn a working intent into a dead message.

## The page

### Rendering

`PitchArtifactService` renders one HTML string from two lists. The rendering itself is a static method over
`List<McpToolCall>` and `List<IntentClassification>`, for the same reason `MetricsService.compute` is: the
arithmetic is then unit-testable without a database.

Content, in order:

1. **Header** — what this is, the generation timestamp, and the headline: *N of 40 tools called across M
   calls*, where 40 is `SILPO_MCP_TOOL_COUNT` and N and M are counted, not claimed.
2. **The tool matrix** — one row per distinct tool, ordered by call count descending then name: tool name,
   calls, failures, flow note. Tools with no note render with an empty cell; the page never invents a
   purpose for a tool it does not recognise.
3. **The intent distribution** — one row per distinct intent, ordered by count descending: intent, count,
   share of all classifications, and a short Ukrainian gloss of what the person said to trigger it. Below
   the table, the three outcomes as a single line: routed / unclassified / failed.
4. **Footer** — that this is a point-in-time snapshot, when it was taken, and that it contains no chat
   content.

Styling is a `<style>` block in the same file — the page must survive being opened from a QR code on a
conference wifi with nothing else to fetch.

### Flow notes

A static `Map<String, String>` in `PitchArtifactService`, keyed by tool name — the one hand-written part of
the page, because "which flow uses this" is knowledge, not data. Every tool the codebase names today gets an
entry (24 of them, from `CartBuildingService`, `LoyaltyBenefitsService`, `ProfileEnrichmentService`,
`OrderHistoryService`, `ReorderService`, `ReadyMealCatalogService` and `PartnerPromotionAdminService`); a
tool that appears in the data without an entry renders blank rather than wrong.

A test asserts every note's key is a tool name that appears somewhere in `src/main/java` — a note for a tool
nobody calls is exactly the kind of stale claim this artifact exists to avoid.

### Serving it

`GET /internal/metrics/pitch-artifact` on `InternalMetricsController`, `text/html`, behind the same
`X-Metrics-Token` as `/internal/metrics/pitch`. `make pitch-artifact` fetches it from a running app and
writes `src/main/resources/static/pitch.html`, which is **committed to git**.

The deployed app (task 59) serves that file at `https://<domain>/pitch.html` — Spring Boot's static resource
handler, no Spring Security in this project, no controller involved.

**Why a committed file and not a public endpoint rendering from the database.** The task forbids a live feed,
and a public endpoint that reads the tables on every request is one in all but name: during the demo window
other people are testing the bot, and their activity would move the numbers under the jury's eyes. Baking the
snapshot into the image makes "point in time" structural rather than a promise. Regenerating is a deliberate
act — run the target, commit, push; the CI/CD loop from task 69 rolls it out. The page carries tool names,
counters and intent names only; no chat text, no product names, no user identifiers, so the public URL leaks
nothing even as it ages.

## Testing

- `PitchArtifactServiceTest` — the static renderer over hand-built lists: the headline counts, ordering by
  call count, a tool without a note rendering an empty cell, an unclassified row counted in the outcome line,
  and the page containing no `user_id` value from the input.
- `FlowNotesTest` (in the same test class) — every note key appears in a `src/main/java` source file.
- `IntentClassificationIntegrationTest` — routing a message writes exactly one row with the routed intent; a
  classification failure writes a `FAILED` row and still returns `false` to the caller.
- `InternalMetricsControllerTest` — the new endpoint 404s with metrics disabled, 403s without the token,
  200s with it. Mirrors the existing case for `/pitch`.

## Out of scope

- Any live view. The page is regenerated by hand before the final recording.
- Per-intent Grafana panels. Task 54's comment stays true: the taxonomy breakdown lives here, not there.
- Rendering the tool matrix from the MCP server's `tools/list`. The denominator 40 is already recorded; what
  this page proves is which of them *this system actually calls*, which only the call log knows.
