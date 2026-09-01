# Inventory diffing and trend tracking — plan

Design: [`../specs/2026-09-01-inventory-trend-design.md`](../specs/2026-09-01-inventory-trend-design.md).
Notion task 13. No schema change: `inventory_trend` exists from task 05.

## Step 1 — the threshold is configuration

`config/CheckinProperties` gains `int removalThreshold`; `application.yml` gains
`removal-threshold: ${CHECKIN_REMOVAL_THRESHOLD:3}`, `application-test.yml` the same value.

## Step 2 — `service/InventoryTrendService`

- `recordCheckin(UUID userId, CheckinDelta delta)` — streak + 1 for `stillHave`, 0 for `runningLow`
  and `goneCompletely`, untouched for anything unmentioned. Upserts through
  `findByUserIdAndItemName`, so the unique constraint is never raced into.
- `getRemovalCandidates(UUID userId)` — item names at or above the threshold, via the existing
  `findByUserIdAndConsecutiveUntouchedCyclesGreaterThanEqual`.
- `getUpcomingNeeds(UUID userId)` — the newest check-in that has a parsed delta; `goneCompletely`
  then `runningLow`, deduplicated.

## Step 3 — record every stored check-in

`CheckinParsingService` calls `recordCheckin` right after `store(...)` succeeds with a delta, so the
invariant holds for whatever channel the answer arrived through.

## Step 4 — feed the removal candidates back into planning

`MealPlanService.describe` gains one line naming items the household has not touched, and only when
there are any. `MealPlanService` takes `InventoryTrendService`; the prompt file gains the matching
rule.

## Step 5 — `InventoryTrendIntegrationTest`

1. three check-ins reporting the same item as `stillHave` make it a removal candidate;
2. two are not enough;
3. `stillHave → goneCompletely → stillHave` scores 1, 0, 1 — a restock does not accumulate;
4. an item nobody mentioned keeps its streak;
5. `runningLow` breaks the streak the same way `goneCompletely` does;
6. `getUpcomingNeeds` puts gone items first, deduplicated;
7. an unparsed check-in is skipped rather than read as "nothing needed";
8. a check-in through the parsing service updates the trend on its own.

## Step 6 — close out

`make format`, `./gradlew test`, `./gradlew build`, commit, tick Notion, ff-merge into `main`.
