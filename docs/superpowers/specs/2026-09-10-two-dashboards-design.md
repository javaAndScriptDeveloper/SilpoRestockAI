# Two sharp Grafana dashboards: Business and Technical (task 75)

## Problem

`observability/grafana/komora-dashboard.json` grew across tasks 54, 63 and 64 into ten rows and forty-odd
panels. It mixes household counts, GMV, cart quality, the partner funnel and MCP latency on one page, so a
judge who is pointed at it for ten seconds sees a wall, not an argument. Task 75 is a clean rebuild, not a
patch: two dashboards, each answering one question, every panel traceable to a sentence the pitch already
makes or to a criterion the jury has published.

Sources read fresh for this design (2026-09-10): the hackathon page's «Критерії оцінювання» and «Завдання»
lists (six items each, unchanged from the Selling Points mapping), the Notion task page, «Selling Points та
Пітч-аргументи», «Сценарій демо-запису», and the current instrumentation in `ObservabilityService`,
`PromotionMetricsService`, `SilpoMcpClientImpl` and `ClaudeApiClientImpl`.

## Goal

- **Business dashboard** (`komora-business`): two visually distinct sections. A viewer tells which one they
  are in from colour alone, before reading a title.
  - **A — цінність для гостя.** No money anywhere. Adoption, accuracy/trust, speed, engagement.
  - **B — цінність і revenue для «Сільпо».** Money-denominated: GMV, average cart, confirmed orders, and the
    featuring block with Attributed Revenue as a number nobody can miss.
- **Technical dashboard** (`komora-observability`, same uid and URL as today): MCP RED metrics per tool as the
  centrepiece, Claude in the same RED framing, then the agent's own reliability signals and plain app health.
- One JSON file per dashboard in `observability/grafana/`, provisioned identically into local Grafana (file
  provider, already wired) and pushed to Grafana Cloud by `make dashboard` (now pushes every file in the
  directory). Neither instance is ever edited by hand.

## What is new in the code

### Intent-to-order speed (the one metric that does not exist yet)

**Definition.** For an order that exists because a chat request was routed by `IntentRouterService`, the
time from the moment the app received that request to the moment the household confirmed the order. It is
a recurring number, one per order, tagged by intent. It differs from task 37's onboarding→first-order, which
is one number per household and measured once.

**Which orders qualify and where the clock starts:**

| Intent (tag value) | Path | `requestedAt` |
|---|---|---|
| `HANGOVER_RELIEF` | router → `AdHocOrderService.buildHangoverReliefOrder` | text received by the router, before the Claude classification call |
| `BLACKOUT` | router → `BlackoutModeService.buildBlackoutOrder`; also `/blackout` | same; for the slash command, the command's arrival |
| `REORDER` | router → `ReorderConfirmationService.startNow`; also `/reorder` | same |
| `DISH_INGREDIENTS_ORDER` | router → `DishRequestService.start` → `AdHocScheduleService.scheduleDishIngredients` → `DishIngredientsService.orderIngredients` | text received by the router. When the dish flow needs another message (photo, «Так, замовляй», a typed name) that later message's arrival is the request |
| `AD_HOC_SCHEDULED_PURCHASE` | `AdHocScheduleScheduler` sweep → `AdHocScheduleService.fire` → `AdHocOrderService.buildAdHocOrder` | the sweep that fires the task, not the sentence days earlier — «до п'ятниці» is a deadline the person chose, not latency |

The weekly cart («Замовити» under the list) and the scheduled reorder cycle are not intent-routed and carry
no trigger. Task 37's metric covers the first cart; the reorder cycle's clock is the check-in interval.

**Carrying the trigger.** A new model record `OrderTrigger(String intent, Instant requestedAt)` is passed
explicitly from the router through the handler to the `present(...)` method that writes the draft. Nothing
is held in a service field (conversation state is the only memory between webhooks, and here the value is
needed only inside the request that writes the draft, so it needs no state at all). The draft stores it in
two new nullable columns on `customer_order`: `trigger_intent VARCHAR(64)` and `requested_at TIMESTAMPTZ`
(changeset `033-order-trigger.yaml`, no backfill — older orders genuinely have no request time).

**Publishing it, twice, for the same reason task 54 publishes GMV as a gauge:**

- At confirmation (both `CartConfirmationService.confirm` and `ReorderConfirmationService.confirm`), a
  Micrometer `Timer` `komora.intent.order` tagged `intent` records `confirmedAt − requestedAt`. This is the
  «a Timer around intent-classification-to-order-confirmation» the task asks for and gives Prometheus a
  histogram for percentiles and per-hour rates.
- On the 30-second snapshot refresh, gauges read off the table so the number survives the constant restarts:
  `komora.intent.order.median` (seconds, tag `intent`, plus an `intent="ALL"` row) and
  `komora.intent.orders` (count, same tags). A median of nothing publishes no series, not zero.

### Guest-value numbers that exist only in `make metrics` today

The pitch table (task 37) has check-in response rate and unedited reorders, but Grafana reads only
Prometheus. Three small DB-derived gauges close that gap, all on the existing snapshot:

- `komora.checkins{stat="prompted"}` = `sum(users.checkin_prompts_sent)`; `{stat="answered"}` =
  `count(checkin)`. The dashboard divides them.
- `komora.reorders{edited="true"|"false"}` = confirmed orders by `edited_before_confirm`, which only reorder
  proposals set — exactly the population «could have been edited».
- `komora.trust.streak{stat="max"}` = `max(trust_level.consecutive_unedited_confirmations)`: the longest run
  of «agent got it right» any household has. (There is no per-order edit count in the schema; the boolean
  plus the streak is what the data honestly supports, and the task says «where meaningful».)

Nothing else is added. The MCP and Claude timers already carry `tool`/`call`/`model`/`outcome` tags, which is
the whole RED breakdown; the technical dashboard only had to be designed against them.

## Business dashboard — panel by panel, with the claim each one backs

Colour is the section marker: every stat in A renders with a **blue** background, every stat in B with a
**green** one; row titles carry the section letter and name. Two rows for A and B, plus a third, also green,
for the featuring block so it has room.

### Row «🧑‍🍳 A · Цінність для гостя (без грошей)»

| Panel | Query (PromQL, all over `${DS}`) | Traces to |
|---|---|---|
| Домогосподарства: старт → профіль → замовлення (bar gauge) | `komora_users_registered`, `komora_users_onboarded`, `komora_users_ordered` | Criterion «Валідація та масштабування»; task text «user count / adoption» |
| Нових профілів за період (stat) | `sum(increase(komora_onboarding_completed_total[$__range]))` | same («new users in period») |
| Активних за 7 днів (stat) | `komora_users_active{window="7d"}` | «цикл живий, не разова новинка» |
| Намір → замовлення, медіана (stat, seconds) | `komora_intent_order_median_seconds{intent="ALL"}` | «мінімум часу на їжу»; task text item A3 |
| Намір → замовлення за типом (bar gauge, seconds) | `komora_intent_order_median_seconds{intent!="ALL"}` | same, per intent — «гастрит, похмілля, п'ятниця… один роутер намірів» |
| Онбординг → перше замовлення (stat) | `komora_onboarding_first_order_seconds{stat="median"}` | Selling Points table row 1 («N хвилин, не днів») |
| Дозамовлень без правок (stat, %) | `100 * komora_reorders{edited="false"} / sum(komora_reorders)` | Selling Points row 3 («агент вчиться»); task text A2 |
| Найдовша серія без правок (stat) | `komora_trust_streak{stat="max"}` | same, second number behind the accuracy story |
| Чек-іни: відповіли (stat, %) with «K з N» bar | `100 * komora_checkins{stat="answered"} / komora_checkins{stat="prompted"}`; bar: both series | Selling Points row 2; task text A4 |
| Позицій списку знайдено в каталозі (stat, %) | `100 * komora_orders_lines{result="resolved"} / sum(komora_orders_lines)` | Selling Points row 4 («94 % … чесна цифра якості») |

No `currencyUAH` unit, no `_uah` series, no «₴» in this row — a test enforces it.

### Row «💰 B · Цінність і revenue для «Сільпо»»

| Panel | Query | Traces to |
|---|---|---|
| GMV (big stat, ₴) | `sum(komora_orders_gmv_uah)` | Criterion «Цінність для бізнесу»; step 13.7 «GMV у кадрі» |
| Середній чек (stat, ₴) | `sum(komora_orders_gmv_uah) / sum(komora_orders_confirmed)` | same |
| Підтверджених замовлень (stat) | `sum(komora_orders_confirmed)` | task text B1 |
| Знижок «Сільпо» у кошиках (stat, ₴) | `sum(komora_orders_savings_uah)` | «Економія — цифра самого «Сільпо»» |
| Замовлень без збереженої суми (small stat) | `sum(komora_orders_value_missing)` | «Чесна межа» paragraph — GMV shows its own coverage |
| Підтверджено за типом, за годину (time series) | `sum by (type) (increase(komora_orders_confirmations_total[1h]))` | the live «it moved» moment in step 13.7 |

### Row «💰 B · Фічеринг: Featured Share Rate і Attributed Revenue»

| Panel | Query | Traces to |
|---|---|---|
| Attributed Revenue — усі розміщення (big stat, ₴) | `komora_promotion_revenue_overall_uah{type="ALL"}` | task text «скільки грошей ми робимо на фічерингу» |
| Featured Share Rate — усі (stat, %; description «аналог Share of Shelf у retail media») | `komora_promotion_share_overall{type="ALL"}` | Selling Points «94 % частки категорії» |
| Attributed Revenue за пулом (bar gauge) | `komora_promotion_revenue_overall_uah{type!="ALL"}` | PAID vs OWN_BRAND split |
| Featured Share Rate за пулом (bar gauge) | `komora_promotion_share_overall{type!="ALL"}` | same |
| PAID_PARTNER: FSR і lift за брендом (bar gauge) | `komora_promotion_share{type="PAID_PARTNER"}`, `komora_promotion_lift{…}` | task text B2 first bullet |
| PAID_PARTNER: Attributed Revenue за брендом (bar gauge, ₴) | `komora_promotion_revenue_uah{type="PAID_PARTNER"}` | second bullet |
| PAID_PARTNER: Conversion Rate між стадіями (bar gauge, max 100) | events ADDED_TO_CART/IMPRESSION and CONFIRMED_ORDER/ADDED_TO_CART per partner | third bullet; the >100 % case is clamped and explained in the description |
| OWN_BRAND_MARGIN_BOOST: the same three | same with the other type | «той самий двигун для власних брендів» |

Dropped from the old dashboard and why: «Завершень онбордингу за годину» (duplicate of the funnel), «Доходять
до першого замовлення %» (the bar gauge shows it), «Позицій у кошику (середнє)» and «Вартість кошика за добу»
(no sentence in the pitch uses them), the raw-events table (drill-down that belongs in `make promotions`),
«Кошиків нижче мінімального замовлення» and «Не знайдено позицій, %» as time series (the resolved-share stat
covers the claim; the ₴799 top-up is a product rule, not a metric anyone pitches).

### Technical dashboard — panel by panel

Row «🔧 MCP «Сільпо» — RED (Rate · Errors · Duration)»: four headline stats across the top — calls per
minute now, success % (5 m), p95 (5 m), distinct tools used over the range — then three wide time series:
**Rate** stacked by tool (`sum by (tool) (rate(komora_mcp_call_seconds_count[1m])) * 60`), **Errors** by
tool and outcome with the overall error % on a second axis, **Duration** p50/p95 by tool
(`histogram_quantile` over `_bucket`), and a horizontal bar gauge «Викликів за інструментом за період» as
the leaderboard a camera can rest on. Traces to: criterion «Якість використання MCP» («14 з 40», «612
викликів»), criterion «Агентність» («живі MCP tool-calls на екрані»), task text T2.

Row «🧠 Claude — RED»: rate by call name, success %, p95 by call. Traces to task text T3 and the
«🧠 думає → 🔧 діє» console line of step 6.

Row «🤖 Агент»: intents routed / unclassified / failed per hour (`komora_intent_classified_total`), cart
builds by outcome with p95 (`komora_cart_build_seconds`), failure messages shown to people
(`komora_failure_message_total`), intent→order p95 per hour from the new timer. Traces to «чесна помилка, не
кошик» and «Кожна нова фіча — новий обробник намірів».

Row «⚙️ Застосунок»: HTTP requests/s and 5xx %, HTTP p95, JVM heap, CPU, uptime, Hikari active
connections, Resilience4j breaker state. Task text T1, «standard app health, cleaned up».

Both dashboards get an `env` template variable (from the Alloy `external_labels`) next to `DS`, default
«All», so a rehearsal laptop and the deployed box never sum into one GMV.

## Testing

- `DashboardJsonTest` runs its name check over every `observability/grafana/*.json`, asserts each uid
  (`komora-business`, `komora-observability`), the `${DS}` binding, and:
  - business: the A row contains no panel with a currency unit or a `_uah` series; the B rows contain
    «Attributed Revenue» and both pool names; the two Conversion Rate panels keep `max = 100` and the
    «не суворо вкладена» sentence; «Share of Shelf» is on the FSR panel.
  - technical: a row whose title contains «RED», panels grouping `komora_mcp_call_seconds` by `tool`.
- `ObservabilityMetricsIntegrationTest`: confirmed orders with `trigger_intent`/`requested_at` publish the
  per-intent and ALL medians and counts; no qualifying order → no series; check-in and reorder gauges.
- `AdHocOrderIntegrationTest`, `ReorderConfirmationIntegrationTest`, `CartConfirmationIntegrationTest`: the
  draft carries the trigger; confirming records `komora_intent_order_seconds` with the intent tag; an order
  without a trigger records nothing.
- Live: drive hangover, dish, reorder and blackout through synthetic webhooks against real Silpo, confirm
  each, and read the new series off `/actuator/prometheus`, local Prometheus and Grafana Cloud.

## Out of scope

Alerting; any change to how placements are resolved or priced; the `make metrics` / `make promotions` text
reports (they stay the pitch-table source; the dashboards now show the same numbers live).
