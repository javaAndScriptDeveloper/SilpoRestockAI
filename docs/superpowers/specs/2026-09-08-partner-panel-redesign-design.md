# Grafana partner section: real funnel, Featured Share Rate, Attributed Revenue (task 64)

## Problem

The «Партнерські розміщення» section of `observability/grafana/komora-dashboard.json` is a flat table of raw
event rows plus one conversion bar. It does not read as a funnel, and it leads with the weakest number
(impressions). Task 63 produced the numbers that actually justify a price — Featured Share Rate, organic
baseline, lift — but they exist only inside the `make promotions` text report. Grafana's only datasource is
Prometheus, so today no panel can reach them at all.

Two consequences shape this design:

1. **This is not a pure visualization task, despite what #64's "out of scope" says.** The numbers #64 must
   display are not published as metrics. Exporting them is inside the work.
2. **Attributed Revenue was never built.** #63 lists it as item 6 and #64 requires it as a headline number,
   but `PromotionMetricsService` computes no money at all. It is computed here.

## Goal

Someone who has never seen the system looks at the section for ten seconds and says «цей бренд забрав X %
категорії і приніс ₴Y атрибутованих продажів» — without narration, and without being misled about how
precise any of it is.

## What gets computed (Java)

### Attributed Revenue

Per placement: the real hryvnia value of the promoted product's own order lines in the orders that fired a
`CONFIRMED_ORDER` event for that placement.

- Source: `partner_promotion_event` rows of type `CONFIRMED_ORDER` carry `order_id` (task 46 already stores
  it). The order's `items_json` carries `BasketItem(silpoProductId, quantity, price)`.
- Line value = `price × quantity` (quantity null → 1), matching how `CartBuildingService` prices a baseline
  line; Silpo's cart node gives a unit price and `quantity × price == subTotal`.
- Only lines whose `silpoProductId` equals the placement's promoted product count. The rest of the basket is
  not the placement's doing and must not be claimed.
- Orders are deduplicated by id: one order contributes once per placement even if events repeat.
- A line with no stored price is **not** counted as zero — it is counted in `ordersMissingPrice` and the
  report says so. Same honesty discipline as #63's baseline: a number that quietly under-reports is still a
  wrong number.

This is the retail-media «Attributed Sales» concept. ARPPU stays deliberately uncomputed — it needs a
placement fee nobody invoiced, and inventing one would break the rule the rest of this metric set follows.

### Overall rollups

Per `promotion_type` (`PAID_PARTNER`, `OWN_BRAND_MARGIN_BOOST`) and for both pools combined:

- **Overall FSR** = resolutions answered by a placement of that pool ÷ resolutions logged **in the categories
  that pool holds a placement in**. The denominator is deliberately *not* every resolution ever logged:
  bread lines nobody bids on would drag the share towards zero and describe nothing. A row is counted once in
  the denominator however many placements of that pool match its category, so two placements in «молоко»
  cannot double-count the milk lines.
- **Total Attributed Revenue** = sum of the per-placement figures in that pool.
- **Active categories** = distinct categories with an `ACTIVE` placement of that pool (#63's «category
  coverage», rolled up).

Per-placement FSR, baseline, method and lift keep #63's definitions untouched.

## What gets published (Prometheus)

`ObservabilityService` already owns every gauge and already re-registers a `MultiGauge` whose tag values are
data. The promotion gauges follow that pattern; the snapshot is rebuilt by the existing
`ObservabilityRefreshScheduler`, so a scrape stays free.

| Meter | Tags | Meaning |
|---|---|---|
| `komora.promotion.events` (existing, tags added) | partner, product, `category`, `type`, event | funnel counts |
| `komora.promotion.share` | partner, product, category, type | per-placement FSR, 0..1 |
| `komora.promotion.baseline` | partner, product, category, type, `method` | organic baseline, 0..1 |
| `komora.promotion.lift` | partner, product, category, type | FSR − baseline, may be negative |
| `komora.promotion.revenue` (uah) | partner, product, category, type | Attributed Revenue |
| `komora.promotion.share.overall` | type (`ALL` included) | pool-wide FSR |
| `komora.promotion.revenue.overall` (uah) | type (`ALL` included) | pool-wide Attributed Revenue |
| `komora.promotion.categories` | type (`ALL` included) | active categories held |

Rules kept from task 54: absolute levels are gauges over a snapshot, never counters — the app restarts and
these are facts about the database. A number that does not exist (no baseline, therefore no lift) is
**omitted**, not published as zero; Prometheus renders a gap and the panel shows «No data», which is the
truth.

`method` as a tag means the panel can show *how* a baseline was derived without a second query — #63's rule
that an approximation is never presented as a measurement, carried into Grafana.

## What the dashboard shows

Section replaced wholesale. Three bands, top to bottom, PAID and OWN_BRAND visually separated by their own
row headers:

**Band 1 — «Партнерські розміщення — зведення»** (the 3-second glance)

- stat: **Featured Share Rate**, all placements, big, percent.
- stat: **Attributed Revenue**, all placements, big, ₴.
- bargauge: FSR split by pool.
- bargauge: Attributed Revenue split by pool.
- a text panel spanning the width carrying the two subtitles the task requires:
  «Featured Share Rate — аналог Share of Shelf у retail media», «Attributed Revenue — аналог Attributed
  Sales», plus the non-nested-funnel footnote.

**Bands 2 and 3 — one per pool**, identical shape so the two read as comparable:

- **bargauge «Воронка»** — three targets in stage order (`1. Показ`, `2. У кошику`, `3. Підтверджено`),
  grouped per partner. Descending bars, not a repeated-value table. Three explicit targets rather than one
  `sum by (event)` because target order is what fixes stage order on the panel.
- **bargauge «Conversion Rate між стадіями»** — «Показ → кошик» and «Кошик → замовлення», each labelled with
  the words *Conversion Rate* in the panel title, `max: 100` so a >100 % stage clamps the bar while the
  printed value stays the real number, with a threshold turning that bar amber and a description explaining
  why it is not a bug.
- **bargauge «Featured Share Rate і lift за брендом»** — FSR per placement plus the lift series beside it,
  both as percent-of-one so they share an axis.
- **bargauge «Attributed Revenue за брендом»** — ₴ per placement.

**Band 4 — «Події розміщень (деталізація)»**, a collapsed row holding the old raw event table. Kept for
drill-down, no longer leading.

### The >100 % case

Found in #63's live run: «Яготинське» showed 5 confirmed orders against 4 add-to-cart events, because a
`CONFIRMED_ORDER` fires for any active placement whose product is in a confirmed order — including a repeat
order that no fresh cart build touched. The funnel is genuinely not nested. Three things handle it, and none
of them is hiding the number: the bar clamps at 100 so nothing renders broken, the value label prints the
true percentage, and the section's text panel says «воронка не суворо вкладена — повторні замовлення
лічаться окремо».

## Testing

- TDD on the service: attributed revenue over a seeded order (line value, not basket total; unrelated lines
  excluded; repeat orders counted once; a priceless line counted as missing rather than as zero), and the
  overall rollups (per-pool split, denominator restricted to held categories, a category with two placements
  not double-counted).
- An integration test asserting the new gauges appear on `/actuator/prometheus` with the expected tags after
  a refresh, and that a placement without a baseline publishes no lift series.
- `DashboardJsonTest` keeps its name check and gains a structural one: the conversion panel caps its bar at
  100 and carries the non-nested explanation, and the section carries the «Share of Shelf» subtitle. These
  are the two acceptance criteria a reader cannot verify by looking at a metric name.

## Out of scope

No new SQL-datasource panels — Grafana here binds one Prometheus through `${DS}` and the committed file must
keep rendering in both the local harness and Grafana Cloud. No partner-facing UI. No A/B baseline.
