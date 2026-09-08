# Partner Panel Redesign Implementation Plan (task 64)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Grafana partner section read as a funnel with Featured Share Rate and Attributed Revenue as headline numbers, globally and per brand, with paid placements and own brands visually separate.

**Architecture:** Compute Attributed Revenue and the pool-level rollups in `PromotionMetricsService` (which already owns FSR/baseline/lift), publish all of it as Micrometer gauges from `ObservabilityService` over the existing snapshot refresh, then rewrite the dashboard section against those series.

**Tech Stack:** Java 21 / Spring Boot 4, Micrometer + Prometheus, Grafana dashboard JSON committed in-repo, JUnit 5 + Testcontainers Postgres.

**Spec:** `docs/superpowers/specs/2026-09-08-partner-panel-redesign-design.md`

## Global Constraints

- Absolute levels are **gauges over the snapshot**, never counters (task 54 rule: the app restarts, the database does not).
- A number we do not have is **omitted or `—`**, never zero (task 63 rule).
- Attributed Revenue = promoted product's own order lines only, `price × quantity`, deduplicated by order id.
- Every new meter name is a `MeterNames` constant; `DashboardJsonTest` fails the build otherwise.
- Dashboard binds its datasource through `${DS}`, uid stays `komora-observability`.
- Constructor injection only; `Service` classes reachable from `Controller`/`Job`/each other; `@Scheduled` stays in `job`.
- `make format` (spotless palantir) before commit; `make test` green.

---

### Task 1: Attributed Revenue per placement

**Files:**
- Modify: `src/main/java/com/silporestockai/model/PromotionMetrics.java`
- Modify: `src/main/java/com/silporestockai/service/PromotionMetricsService.java`
- Modify: `src/main/java/com/silporestockai/repository/PartnerPromotionEventRepository.java`
- Test: `src/test/java/com/silporestockai/integration/PromotionMetricsIntegrationTest.java`

**Interfaces:**
- Produces: `PromotionMetrics.attributedRevenue()` → `BigDecimal` (never null, scale 2),
  `PromotionMetrics.ordersMissingPrice()` → `long`;
  `PartnerPromotionEventRepository.findByEventType(PartnerPromotionEventType)` → `List<PartnerPromotionEvent>`.

- [ ] **Step 1: Write the failing tests** in `PromotionMetricsIntegrationTest`:
  - a confirmed order containing the promoted product (price 47.90 × qty 2) plus an unrelated line → `attributedRevenue` is `95.80`, not the basket total;
  - two `CONFIRMED_ORDER` events pointing at the same order id → counted once;
  - a promoted line with `price == null` → revenue unchanged, `ordersMissingPrice` is 1;
  - a placement with no confirmed order → `BigDecimal.ZERO`.
- [ ] **Step 2: Run** `./gradlew test --tests '*PromotionMetricsIntegrationTest*'` — expect compile failure (`attributedRevenue()` undefined).
- [ ] **Step 3: Implement.** Add the two components to the `PromotionMetrics` record. In `metricsFor`, take a pre-loaded `Map<UUID, CustomerOrder>` (loaded once in `metrics()` from the distinct order ids of all `CONFIRMED_ORDER` events, via `customerOrderRepository.findAllById`) and sum `price × (quantity == null ? ONE : quantity)` over items whose `silpoProductId` equals the placement's, `setScale(2, HALF_UP)`.
- [ ] **Step 4: Run** the same command — expect PASS.
- [ ] **Step 5: Commit** `feat: attribute real hryvnia revenue to each placement`.

---

### Task 2: Pool rollups (overall FSR, revenue, category coverage)

**Files:**
- Create: `src/main/java/com/silporestockai/model/PromotionRollup.java`
- Modify: `src/main/java/com/silporestockai/service/PromotionMetricsService.java`
- Test: `src/test/java/com/silporestockai/integration/PromotionMetricsIntegrationTest.java`

**Interfaces:**
- Produces: `record PromotionRollup(PromotionType type, Double featuredShareRate, long featuredResolutions, long categoryResolutions, BigDecimal attributedRevenue, int activeCategories)` with `type == null` meaning both pools; `PromotionMetricsService.rollups()` → `List<PromotionRollup>` (PAID, OWN_BRAND, then the combined row).

- [ ] **Step 1: Write the failing tests:**
  - denominator counts only resolutions in categories that pool holds a placement in — a logged «хліб» line with no placement does not move the number;
  - two placements on «молоко» count the milk rows **once** in the denominator;
  - the combined row's revenue equals the sum of the two pools.
- [ ] **Step 2: Run** the test — expect compile failure.
- [ ] **Step 3: Implement** `rollups()` using `CategoryWords.matches` against the set of that pool's placement categories; `activeCategories` counts distinct categories of `ACTIVE` placements; share is null when the denominator is 0.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat: roll placement metrics up per value pool`.

---

### Task 3: Revenue and rollups in the `make promotions` report

**Files:**
- Modify: `src/main/java/com/silporestockai/service/PromotionMetricsService.java` (`report()`)
- Test: `src/test/java/com/silporestockai/integration/PromotionMetricsIntegrationTest.java`

- [ ] **Step 1: Write the failing test** — the report contains an «Атрибутовано, ₴» column, a per-section total line, and a footnote naming the count of order lines with no stored price when there are any.
- [ ] **Step 2: Run** — expect FAIL (text absent).
- [ ] **Step 3: Implement** the extra column and the rollup line per section; keep the existing non-nested-funnel footnote.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat: report attributed revenue next to share and lift`.

---

### Task 4: Publish the placement metrics to Prometheus

**Files:**
- Modify: `src/main/java/com/silporestockai/utils/MeterNames.java`
- Modify: `src/main/java/com/silporestockai/service/ObservabilityService.java`
- Test: `src/test/java/com/silporestockai/integration/ObservabilityMetricsIntegrationTest.java`

**Interfaces:**
- Consumes: `PromotionMetricsService.metrics()`, `PromotionMetricsService.rollups()`.
- Produces meters `komora.promotion.share`, `komora.promotion.baseline`, `komora.promotion.lift`, `komora.promotion.revenue` (uah), `komora.promotion.share.overall`, `komora.promotion.revenue.overall` (uah), `komora.promotion.categories`; tags `category` and `type` added to `komora.promotion.events`; tag keys `TAG_CATEGORY`, `TAG_METHOD`, `TAG_POOL_ALL = "ALL"`.

- [ ] **Step 1: Write the failing test** — after seeding a placement plus resolutions and calling `refresh()`, `/actuator/prometheus` contains `komora_promotion_share{...type="OWN_BRAND_MARGIN_BOOST"...}`, `komora_promotion_revenue_uah`, `komora_promotion_share_overall{type="ALL"}`; and a placement whose baseline method is `UNKNOWN` publishes **no** `komora_promotion_lift` series for it.
- [ ] **Step 2: Run** `./gradlew test --tests '*ObservabilityMetricsIntegrationTest*'` — expect FAIL.
- [ ] **Step 3: Implement:** one `MultiGauge` per new meter, re-registered inside `refresh()` with `overwrite = true`; rows for null values are simply not emitted. `komora.promotion.events` rows gain the category and type tags from the placement (extend `PromotionEventCount` and `funnelCounts()` to select them).
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Commit** `feat: publish share, lift and attributed revenue as gauges`.

---

### Task 5: Rewrite the dashboard's partner section

**Files:**
- Modify: `observability/grafana/komora-dashboard.json`
- Modify: `src/test/java/com/silporestockai/unit/DashboardJsonTest.java`

- [ ] **Step 1: Write the failing tests** in `DashboardJsonTest`:
  - the section carries the «аналог Share of Shelf у retail media» subtitle;
  - a panel titled with «Conversion Rate» exists for each pool, has `fieldConfig.defaults.max == 100`, and its description mentions «не суворо вкладена»;
  - the two pools each have their own row header naming `PAID_PARTNER` / `OWN_BRAND_MARGIN_BOOST`;
  - the raw event table sits inside a `collapsed: true` row.
- [ ] **Step 2: Run** `./gradlew test --tests '*DashboardJsonTest*'` — expect FAIL.
- [ ] **Step 3: Implement** by rewriting the panel list with a Python script (`json.load` → rebuild → `json.dump(ensure_ascii=False, indent=2)`), keeping every other section byte-identical and shifting the «Надійність» row and its panels down by the height the new section adds. Layout per the spec: summary band (2 stats + 2 bargauges + text legend), then one band per pool (funnel bargauge with three ordered targets, Conversion Rate bargauge capped at 100, FSR+lift bargauge, revenue bargauge), then the collapsed detail row.
- [ ] **Step 4: Run** `./gradlew test --tests '*DashboardJsonTest*'` — expect PASS.
- [ ] **Step 5: Commit** `feat: rebuild the partner dashboard section as a real funnel`.

---

### Task 6: Verify live, then write the session record

**Files:**
- Modify: `docs/RUNBOOK.md` (a `### Task 64:` live-check list)
- Modify: `docs/OVERNIGHT_SUMMARY.md` (a `# Session 14` section)

- [ ] **Step 1: Run** `make format && make test` — full suite green, no bootRun holding `build/classes`.
- [ ] **Step 2:** Start the app against the seeded live data, hit `/actuator/prometheus`, confirm every new series is present with real values from task 63's live run.
- [ ] **Step 3:** Render the dashboard locally (`make observability-local-up`) and read the section as a stranger would: does it say «цей бренд забрав X % категорії і приніс ₴Y» in ten seconds?
- [ ] **Step 4:** Write the RUNBOOK check-list and the session section; update Notion task 64 status and demo-script step 13.5.
- [ ] **Step 5: Commit** `docs: record the partner panel redesign`.

---

## Self-Review

- Spec coverage: revenue → Task 1; rollups → Task 2; report → Task 3; gauges → Task 4; funnel, Conversion Rate labels, Share of Shelf subtitle, global+per-partner, pool split, >100 % handling, collapsed raw table → Task 5; live read + docs → Task 6.
- No placeholders; each task ends at a committable, independently testable deliverable.
- Names used consistently across tasks: `attributedRevenue`, `ordersMissingPrice`, `PromotionRollup`, `rollups()`, the seven meter constants.
