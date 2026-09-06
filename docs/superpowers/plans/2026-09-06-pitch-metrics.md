# Pitch validation metrics (task 37) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Five quotable numbers from real usage, computed from the database on demand and printed as a markdown report ready to paste into the pitch.

**Architecture:** Almost everything is already timestamped. Three small write-path additions close the gaps the spec's own metric list has: a per-user counter of check-in prompts sent (only the *last* timestamp existed), an `unresolved_count` and an `edited_before_confirm` flag on `customer_order`, and an `mcp_tool_call` log table fed by an application event the MCP client publishes (the client layer may not touch repositories — ArchUnit). `MetricsService` reads the tables and computes; `InternalMetricsController` serves the markdown at `GET /internal/metrics/pitch`, gated by a `METRICS_TOKEN` header and absent (404) when the token is unset. `make metrics` curls it.

**Tech Stack:** Liquibase `025`, JPA, Spring events, Spring MVC, MockMvc.

**Spec:** Notion task 37 (`3d27227d-ef1c-81a1-bd23-e74ee56b6764`).

## Global Constraints

- Layers: `client` → publishes `McpToolCalledEvent` (model); `service` listens and persists. No repository access from `client`.
- New column on `users` needs a default so existing rows stay valid (`ddl-auto: validate`).
- No dashboard, no warehouse. One endpoint, one Makefile target.
- The report must say "n=…" next to every ratio; small honest numbers beat large invented ones.

---

### Task 1: Write-path gaps (schema + three one-line hooks)

**Files:**
- Create: `src/main/resources/db/changelog/changes/025-pitch-metrics.yaml`
- Modify: `entity/User.java` (+`checkinPromptsSent int`), `entity/CustomerOrder.java` (+`unresolvedCount Integer`, +`editedBeforeConfirm Boolean`)
- Create: `entity/McpToolCall.java`, `repository/McpToolCallRepository.java`, `model/McpToolCalledEvent.java`, `service/McpCallLogService.java`
- Modify: `client/mcp/SilpoMcpClientImpl.java` (publish the event after every `callTool`, success or error)
- Modify: `service/CheckinPromptService.java` (`prompt` increments the counter), `service/CartConfirmationService.java` and `service/ReorderConfirmationService.java` (`unresolvedCount` on the draft; `editedBeforeConfirm` on reorder confirm)

### Task 2: `MetricsService` + report

**Files:**
- Create: `model/PitchMetrics.java` (record with the five numbers and their sample sizes), `service/MetricsService.java` (`PitchMetrics compute()` over repositories; `static PitchMetrics compute(users, profiles, orders, checkins, toolCalls)` pure; `String markdown(PitchMetrics)`)
- Test: `unit/MetricsServiceTest.java` (pure compute over hand-built lists; markdown contains each line)

Metrics:
1. Onboarding → first confirmed order: per user, `users.created_at` → min `customer_order.confirmed_at` (`CONFIRMED`); median and fastest, n = users with ≥1 confirmed order; also onboarded-profile count.
2. Check-in response rate: `count(checkin)` / `sum(users.checkin_prompts_sent)`, both shown.
3. Reorder confirmations with zero edits: over `CONFIRMED` orders of type `REORDER` with a non-null flag.
4. Resolved vs unresolved per cart build: mean `items.size()` and mean `unresolved_count` over orders with a non-null count; plus overall resolve rate.
5. MCP tools: distinct tool names, total calls, error calls, from `mcp_tool_call`.

### Task 3: Endpoint, config, Makefile

**Files:**
- Create: `config/MetricsProperties.java` (`komora.metrics.token`), `controller/InternalMetricsController.java`
- Modify: `application.yml` (`komora.metrics.token: ${METRICS_TOKEN:}`), `.env.example`, `Makefile` (`metrics` target)
- Test: `integration/InternalMetricsIntegrationTest.java` — 404 when no token configured; 403 wrong header; 200 text/markdown with the right header, and the MCP log row written by a real `callTool` against the stub shows up in the report.

### Task 4: Docs + Notion

- RUNBOOK: «Task 37: pull the pitch numbers» — `make metrics` after a rehearsal.
- Notion: task 37 → In review (criterion 4 needs a rehearsal run against a live account — the local DB has one user and zero orders today); «Selling Points» gets the exact command and a placeholder table to fill.
