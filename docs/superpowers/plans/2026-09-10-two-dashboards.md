# Two Dashboards Implementation Plan (task 75)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the one cluttered Grafana dashboard with a Business dashboard (guest value / Silpo revenue, two colour-coded sections) and a Technical dashboard (MCP RED per tool), and instrument the one missing number — intent-to-order speed.

**Architecture:** An `OrderTrigger(intent, requestedAt)` record travels explicitly from `IntentRouterService` through each order-building handler to the `present(...)` that writes the draft; the draft stores it in two new `customer_order` columns; both confirm paths record a `Timer` from it, and the 30-second snapshot refresh publishes restart-safe medians and counts as gauges. Three more DB-derived gauges (check-ins, reorders, trust streak) lift task 37's pitch numbers into Prometheus. The two dashboards are fresh JSON files in `observability/grafana/`, provisioned by the existing local file provider and pushed to Grafana Cloud by `make dashboard`.

**Tech Stack:** Java 21 / Spring Boot 4, Micrometer + Prometheus, Liquibase, Grafana 11 (local) / Grafana Cloud 13, JUnit 5 + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-10-two-dashboards-design.md`

## Global Constraints

- Absolute levels are gauges over the snapshot; rates and latencies are counters/timers at the call site. A number that does not exist publishes **no series**, never zero.
- Every meter name is a `MeterNames` constant; `DashboardJsonTest` maps every `komora_*` series in every dashboard file back to one.
- Liquibase owns the schema (`ddl-auto: validate`): new columns need `033-order-trigger.yaml`.
- Conversation state is the only memory between webhooks; nothing is held in a service field.
- Constructor injection; `Service` reachable only from `Controller`/`Job`/other services; `@Scheduled` stays in `job`.
- Dashboards bind their datasource through `${DS}`; uids are `komora-business` and `komora-observability`.
- Section A of the business dashboard carries no currency unit and no `_uah` series.
- `make format` before every commit; `make test` green before the final commit (stop the running app first — it shares `build/classes`).

---

### Task 1: `OrderTrigger` on the order row

**Files:**
- Create: `src/main/java/com/silporestockai/model/OrderTrigger.java`
- Create: `src/main/resources/db/changelog/changes/033-order-trigger.yaml`
- Modify: `src/main/java/com/silporestockai/entity/CustomerOrder.java` (after `toppedUpCount`)
- Modify: `src/main/java/com/silporestockai/service/CartConfirmationService.java:94-150`
- Modify: `src/main/java/com/silporestockai/service/ReorderConfirmationService.java:102-160`
- Test: `src/test/java/com/silporestockai/integration/AdHocOrderIntegrationTest.java`, `ReorderConfirmationIntegrationTest.java`

**Interfaces:**
- Produces: `record OrderTrigger(String intent, Instant requestedAt)` with `static OrderTrigger of(String intent, Instant requestedAt)`;
  `CustomerOrder.getTriggerIntent()`/`getRequestedAt()`;
  `CartConfirmationService.present(User, List<ShoppingListItem>, OrderType, boolean preferDiscounted, OrderTrigger trigger)` (the existing 4-arg overload delegates with `null`);
  `ReorderConfirmationService.present(User, DeltaOrder, OrderTrigger)` (the existing 2-arg overload delegates with `null`).

- [ ] **Step 1: Write the failing tests.** In `AdHocOrderIntegrationTest` add:

```java
@Test
void hangoverDraftRemembersWhichIntentAskedForItAndWhen() {
    CLAUDE.respondWithText(MATCH_WATER_AND_ISOTONIC);
    Instant asked = Instant.now().minusSeconds(4);
    adHocOrderService.buildHangoverReliefOrder(user, OrderTrigger.of("HANGOVER_RELIEF", asked));

    CustomerOrder draft = customerOrderRepository
            .findByUserIdAndStatus(user.getId(), OrderStatus.DRAFT)
            .getFirst();
    assertThat(draft.getTriggerIntent()).isEqualTo("HANGOVER_RELIEF");
    assertThat(draft.getRequestedAt()).isEqualTo(asked);
}
```

In `ReorderConfirmationIntegrationTest` change the `present()` helper to `reorderConfirmationService.present(user, order, OrderTrigger.of("REORDER", Instant.now()))` and add:

```java
@Test
void reorderDraftCarriesTheTriggerThatStartedIt() {
    present();
    CustomerOrder draft = customerOrderRepository
            .findByUserIdAndStatus(user.getId(), OrderStatus.DRAFT)
            .getFirst();
    assertThat(draft.getTriggerIntent()).isEqualTo("REORDER");
    assertThat(draft.getRequestedAt()).isNotNull();
}
```

- [ ] **Step 2: Run** `./gradlew test --tests '*AdHocOrderIntegrationTest*' --tests '*ReorderConfirmationIntegrationTest*'` — expect compile failure (`OrderTrigger` undefined).
- [ ] **Step 3: Implement.**

`OrderTrigger.java`:
```java
package com.silporestockai.model;

import java.time.Instant;

/**
 * Why an order is being built, and when the person asked for it (task 75).
 *
 * <p>Passed explicitly from the intent router to the {@code present(...)} that writes the draft, so no service
 * holds it in a field. The draft stores it; confirmation reads it back and records intent→order speed. Orders
 * nobody asked for in a sentence — the weekly cart, the scheduled reorder — carry none.
 *
 * @param intent the router's intent name, e.g. {@code HANGOVER_RELIEF}; a Prometheus tag value
 * @param requestedAt when the request reached the app — before any model call, so the number is honest
 */
public record OrderTrigger(String intent, Instant requestedAt) {

    public static OrderTrigger of(String intent, Instant requestedAt) {
        return new OrderTrigger(intent, requestedAt);
    }
}
```

`033-order-trigger.yaml`:
```yaml
databaseChangeLog:
  - changeSet:
      id: 033-order-trigger
      author: komora
      comment: >-
        Task 75: intent-to-order speed. The router knew which intent built an order and when the sentence
        arrived, but nothing wrote it down, so «намір → замовлення» could not be measured after the fact.
        Nullable, no backfill: an order the weekly list or the reorder cycle built has no request time,
        and older intent orders genuinely lost theirs.
      changes:
        - addColumn:
            tableName: customer_order
            columns:
              - column:
                  name: trigger_intent
                  type: VARCHAR(64)
              - column:
                  name: requested_at
                  type: TIMESTAMP WITH TIME ZONE
```

`CustomerOrder`: add
```java
/** Task 75: the router intent that asked for this order, or null for the weekly cart and the reorder cycle. */
@Column(name = "trigger_intent", length = 64)
private String triggerIntent;

/** Task 75: when that request reached the app; intent→order speed is confirmedAt − this. */
@Column(name = "requested_at")
private Instant requestedAt;
```

`CartConfirmationService`: make the 4-arg `present` delegate to a new 5-arg one; in the builder add `.triggerIntent(trigger == null ? null : trigger.intent()).requestedAt(trigger == null ? null : trigger.requestedAt())`. Same in `ReorderConfirmationService.present(User, DeltaOrder, OrderTrigger)`; `startNow(User user, OrderTrigger trigger)` passes it through. `AdHocOrderService.buildHangoverReliefOrder(User, OrderTrigger)` and `buildAdHocOrder(User, String, Instant, OrderTrigger)` pass it to `present`. Update `AdHocScheduleService.fire` to call `buildAdHocOrder(user, theme, triggerAt, OrderTrigger.of("AD_HOC_SCHEDULED_PURCHASE", Instant.now()))` for now (Task 3 refines dish).
- [ ] **Step 4: Run** the two test classes — expect PASS. Also `./gradlew test --tests '*ArchitectureTest*'`.
- [ ] **Step 5: Commit** `Remember which intent asked for an order, and when`.

---

### Task 2: Timer at confirmation + restart-safe gauges

**Files:**
- Modify: `src/main/java/com/silporestockai/utils/MeterNames.java`
- Create: `src/main/java/com/silporestockai/model/IntentOrderDelay.java`, `IntentOrderStat.java`
- Modify: `src/main/java/com/silporestockai/model/ObservabilitySnapshot.java`
- Modify: `src/main/java/com/silporestockai/repository/CustomerOrderRepository.java`, `UserRepository.java`, `TrustLevelRepository.java`
- Modify: `src/main/java/com/silporestockai/service/ObservabilityService.java`
- Modify: `CartConfirmationService.confirm` (after `recordConfirmedOrder`), `ReorderConfirmationService.confirm` (same)
- Test: `ObservabilityMetricsIntegrationTest`

**Interfaces:**
- Produces: `MeterNames.INTENT_ORDER = "komora.intent.order"` (timer, tag `intent`), `INTENT_ORDER_MEDIAN = "komora.intent.order.median"` (gauge, seconds, tag `intent`, plus `ALL`), `INTENT_ORDERS = "komora.intent.orders"`, `CHECKINS = "komora.checkins"` (tag `stat` = `prompted|answered`), `REORDERS = "komora.reorders"` (tag `edited` = `true|false`), `TRUST_STREAK = "komora.trust.streak"` (tag `stat="max"`), `TAG_INTENT = "intent"`, `TAG_EDITED = "edited"`, `INTENT_ALL = "ALL"`;
  `ObservabilityService.recordIntentToOrder(String intent, Duration took)`.

- [ ] **Step 1: Write the failing tests** in `ObservabilityMetricsIntegrationTest`:

```java
@Test
void publishesIntentToOrderMedianPerIntentAndOverall() throws Exception {
    User user = newUser(4_075_001L);
    intentOrder(user.getId(), "HANGOVER_RELIEF", 90);
    intentOrder(user.getId(), "HANGOVER_RELIEF", 150);
    intentOrder(user.getId(), "DISH_INGREDIENTS_ORDER", 400);
    // A weekly cart: no intent, must not appear anywhere in these series.
    confirmedOrder(user.getId(), OrderType.INITIAL, new BigDecimal("900.00"), new BigDecimal("900.00"), 3);

    observabilityService.refresh();
    String body = scrape();

    assertThat(valueOf(body, "komora_intent_order_median_seconds", "intent=\"HANGOVER_RELIEF\"")).isEqualTo(120.0);
    assertThat(valueOf(body, "komora_intent_order_median_seconds", "intent=\"DISH_INGREDIENTS_ORDER\"")).isEqualTo(400.0);
    assertThat(valueOf(body, "komora_intent_order_median_seconds", "intent=\"ALL\"")).isEqualTo(150.0);
    assertThat(valueOf(body, "komora_intent_orders", "intent=\"ALL\"")).isEqualTo(3.0);
    assertThat(valueOf(body, "komora_intent_orders", "intent=\"HANGOVER_RELIEF\"")).isEqualTo(2.0);
}

@Test
void noIntentOrdersMeansNoIntentSeriesRatherThanZero() throws Exception {
    observabilityService.refresh();
    assertThat(scrape()).doesNotContain("komora_intent_order_median_seconds{");
}

@Test
void recordsTheIntentToOrderTimerAtConfirmation() throws Exception {
    observabilityService.recordIntentToOrder("BLACKOUT", java.time.Duration.ofSeconds(42));
    assertThat(scrape()).contains("komora_intent_order_seconds_count{").contains("intent=\"BLACKOUT\"");
}

@Test
void publishesCheckinReorderAndTrustGaugesForTheGuestSection() throws Exception {
    User user = newUser(4_075_002L);
    user.setCheckinPromptsSent(5);
    userRepository.save(user);
    checkinRepository.save(Checkin.builder().id(UUID.randomUUID()).userId(user.getId())
            .rawText("молоко закінчилось").receivedAt(Instant.now()).build());
    reorder(user.getId(), false);
    reorder(user.getId(), false);
    reorder(user.getId(), true);
    trustLevelRepository.save(TrustLevel.builder().id(UUID.randomUUID()).userId(user.getId())
            .consecutiveUneditedConfirmations(4).build());

    observabilityService.refresh();
    String body = scrape();

    assertThat(valueOf(body, "komora_checkins", "stat=\"prompted\"")).isEqualTo(5.0);
    assertThat(valueOf(body, "komora_checkins", "stat=\"answered\"")).isEqualTo(1.0);
    assertThat(valueOf(body, "komora_reorders", "edited=\"false\"")).isEqualTo(2.0);
    assertThat(valueOf(body, "komora_reorders", "edited=\"true\"")).isEqualTo(1.0);
    assertThat(valueOf(body, "komora_trust_streak", "stat=\"max\"")).isEqualTo(4.0);
}
```
with helpers `intentOrder(userId, intent, seconds)` (a CONFIRMED order with `triggerIntent`, `requestedAt = now − seconds`, `confirmedAt = now`) and `reorder(userId, edited)` (CONFIRMED `SCHEDULED_REORDER` with `editedBeforeConfirm`). Check `Checkin`'s builder fields before writing (`grep -n 'private' entity/Checkin.java`). Add `@BeforeEach` cleanup for `checkinRepository` and `trustLevelRepository`.

- [ ] **Step 2: Run** `./gradlew test --tests '*ObservabilityMetricsIntegrationTest*'` — expect compile failure.
- [ ] **Step 3: Implement.**

`IntentOrderDelay.java` (projection): `public record IntentOrderDelay(String intent, Instant requestedAt, Instant confirmedAt) {}`.
`IntentOrderStat.java`: `public record IntentOrderStat(String intent, long count, Duration median) {}`.

`CustomerOrderRepository`:
```java
@Query("""
        select new com.silporestockai.model.IntentOrderDelay(o.triggerIntent, o.requestedAt, o.confirmedAt)
        from CustomerOrder o
        where o.status = com.silporestockai.model.OrderStatus.CONFIRMED
          and o.triggerIntent is not null
          and o.requestedAt is not null
          and o.confirmedAt is not null
        """)
List<IntentOrderDelay> intentOrderDelays();

long countByStatusAndEditedBeforeConfirm(OrderStatus status, Boolean editedBeforeConfirm);
```
`UserRepository`: `@Query("select coalesce(sum(u.checkinPromptsSent), 0) from User u") long checkinPromptsSent();`
`TrustLevelRepository`: `@Query("select coalesce(max(t.consecutiveUneditedConfirmations), 0) from TrustLevel t") int longestUneditedStreak();`

`ObservabilitySnapshot`: add `List<IntentOrderStat> intentOrders, long checkinPromptsSent, long checkinsAnswered, long reordersUnedited, long reordersEdited, int trustStreakMax` (update `empty()`).

`ObservabilityService.refresh()`: compute per-intent stats — group `intentOrderDelays()` by intent, durations `confirmedAt − requestedAt` skipping negatives, sorted, `MetricsService.median`; add an `ALL` row over every duration. Register a `MultiGauge intentMedianGauge` (`baseUnit("seconds")`) and `intentCountGauge`; rows only where median != null. Plain gauges: `komora.checkins{stat}`, `komora.reorders{edited}`, `komora.trust.streak{stat="max"}`. Add:
```java
/** Intent→order speed (task 75): recorded when an intent-built order is confirmed. */
public void recordIntentToOrder(String intent, Duration took) {
    meterRegistry.timer(MeterNames.INTENT_ORDER, MeterNames.TAG_INTENT, intent).record(took);
}
```
In both `confirm` methods, right after `recordConfirmedOrder(...)`:
```java
if (order.getTriggerIntent() != null && order.getRequestedAt() != null) {
    observabilityService.recordIntentToOrder(
            order.getTriggerIntent(), Duration.between(order.getRequestedAt(), order.getConfirmedAt()));
}
```
Add `komora.intent.order` to the SLO/histogram config in `application.yml` next to `komora.cart.build` (`grep -n 'komora.cart.build' application.yml`) so `_bucket` exists.
- [ ] **Step 4: Run** the test class — expect PASS.
- [ ] **Step 5: Commit** `Publish intent-to-order speed, check-in and reorder numbers as metrics`.

---

### Task 3: Thread the trigger from the router and the commands

**Files:**
- Modify: `IntentRouterService.java:171-190, 203-250`; `TelegramRoutingService.java:504-511`; `BlackoutModeService.java:52`; `DishRequestService.java:43-140`; `AdHocScheduleService.java:63-115`; `DishIngredientsService.java:70`
- Test: `IntentRouterIntegrationTest`, `BlackoutModeIntegrationTest`, `DishIngredientsIntegrationTest`

**Interfaces:**
- `IntentRouterService.tryRoute` captures `Instant receivedAt = Instant.now()` before `completeStructured`; `dispatch(User, String, ClassifiedIntent, IntentType, Instant receivedAt)` builds `OrderTrigger.of(intent.name(), receivedAt)` for `REORDER`, `HANGOVER_RELIEF`, `BLACKOUT`, `DISH_INGREDIENTS_ORDER`.
- `BlackoutModeService.buildBlackoutOrder(User, OrderTrigger)`; `DishRequestService.start(User, String, OrderTrigger)`; `AdHocScheduleService.scheduleDishIngredients(User, String, OrderTrigger)`; `DishIngredientsService.orderIngredients(User, String, OrderTrigger)`; `AdHocScheduleService.fire(task, user, trigger)` — sweep passes `OrderTrigger.of("AD_HOC_SCHEDULED_PURCHASE", Instant.now())`.
- Slash commands: `/reorder` → `OrderTrigger.of("REORDER", Instant.now())`, `/blackout` → `OrderTrigger.of("BLACKOUT", Instant.now())`. Dish continuation messages (`handle`, `startFromPhoto`) → `OrderTrigger.of("DISH_INGREDIENTS_ORDER", Instant.now())`.

- [ ] **Step 1: Write the failing test** in `IntentRouterIntegrationTest` (find how it drives text — `grep -n 'route(\|webhook' …`) asserting that after a hangover sentence the draft's `triggerIntent` is `HANGOVER_RELIEF` and `requestedAt` is before the draft's `createdAt`. In `DishIngredientsIntegrationTest`, after a dish order the draft's `triggerIntent` is `DISH_INGREDIENTS_ORDER`.
- [ ] **Step 2: Run** those classes — expect FAIL (null intent / compile error).
- [ ] **Step 3: Implement** the signatures above; fix every caller `./gradlew compileJava compileTestJava` reports.
- [ ] **Step 4: Run** `./gradlew test --tests '*IntentRouter*' --tests '*Dish*' --tests '*Blackout*' --tests '*AdHoc*' --tests '*Reorder*'` — PASS.
- [ ] **Step 5: Commit** `Start the intent-to-order clock where the sentence arrives`.

---

### Task 4: The two dashboard files + `make dashboard` for both

**Files:**
- Delete: `observability/grafana/komora-dashboard.json`
- Create: `observability/grafana/komora-business.json`, `observability/grafana/komora-observability.json` (generated by a throwaway Python script in the scratchpad so panel geometry is computed, not hand-typed)
- Modify: `Makefile:82-89`, `src/test/java/com/silporestockai/unit/DashboardJsonTest.java`

**Interfaces:** panel queries exactly as the spec's tables; `templating.list = [DS (datasource, prometheus), env (query `label_values(komora_users_registered, env)`, includeAll, default All)]`; every selector carries `env=~"$env"`.

- [ ] **Step 1: Rewrite `DashboardJsonTest`** to iterate `Files.list(Path.of("observability/grafana"))`: name check for every file; per-file uid assertions; business: rows titled with «A ·» and «B ·», no panel under the A row with `fieldConfig.defaults.unit` starting with `currency` or an expr containing `_uah`; B rows contain a panel titled with «Attributed Revenue» and titles containing `PAID_PARTNER` and `OWN_BRAND_MARGIN_BOOST`; the two «Conversion Rate» panels keep `max == 100` and «не суворо вкладена»; the FSR panel description contains «Share of Shelf»; technical: a row title containing «RED» and an expr containing `by (tool)`. Run — expect FAIL (files missing).
- [ ] **Step 2: Generate the JSON** with the script (`gen_dashboards.py`): helpers `stat(title, expr, unit, color, w, h, description)`, `bargauge(...)`, `timeseries(...)`, `row(title)`; stats use `colorMode: "background"`, fixed colour `blue` for A, `green` for B; grid positions computed left-to-right. Write both files pretty-printed.
- [ ] **Step 3: Makefile** — `dashboard:` loops `for f in observability/grafana/*.json`, pushes each with `jq -n --slurpfile d $$f '{dashboard: ($$d[0] + {id: null}), overwrite: true, message: "komora dashboards"}'`, prints each URL. Update `observability-local-up` echo to list both URLs.
- [ ] **Step 4: Run** `./gradlew test --tests '*DashboardJsonTest*'` — PASS. Reload local Grafana provisioning (`docker restart komora-grafana`), `curl -s localhost:3000/api/dashboards/uid/komora-business | jq .meta.url` and the same for `komora-observability`.
- [ ] **Step 5: Commit** `Rebuild Grafana as a Business and a Technical dashboard`.

---

### Task 5: Push, live traffic, verification

- [ ] **Step 1:** stop the running app, `make test` (full suite, Docker up), `make format`, restart the app (`setsid nohup make run`), wait for `/actuator/health`.
- [ ] **Step 2:** `make dashboard` → both cloud URLs; `curl` the cloud `api/dashboards/uid/...` for both and diff `dashboard.panels` titles against the local files.
- [ ] **Step 3:** Drive real traffic through synthetic webhooks (`docs/RUNBOOK.md` → 16 / memory notes): hangover sentence → `cart:confirm` (top-up if below ₴799), «замов усе для карбонари» → confirm, «що треба докупити?» → confirm, «світло вимкнули» → confirm. Reset `conversation_state.current_flow` to `NONE` between steps. Pace with `python3 -c "import time; time.sleep(N)"`.
- [ ] **Step 4:** Verify: `curl localhost:8080/actuator/prometheus | grep -E 'komora_intent_order|komora_checkins|komora_reorders|komora_trust'`; local Prometheus `histogram_quantile` over `komora_mcp_call_seconds_bucket` by tool; cloud Prometheus proxy queries for `komora_intent_order_median_seconds` and `komora_promotion_revenue_overall_uah`.
- [ ] **Step 5:** Docs: RUNBOOK §16 (two URLs, `make dashboard` pushes both, the new series, the live check), `.env.example` comment, `docs/OVERNIGHT_SUMMARY.md` «Session 18», `docs/OVERNIGHT_QUESTIONS.md` decisions (scheduled purchase clock starts at the sweep; commands count as intents; no per-order edit count exists). Notion: Selling Points (dashboard section → two URLs, what to point at), Demo script step 13.7 (+ 13.5 pointer), task 75 status. Commit `Document the two dashboards and the live check`, push.
