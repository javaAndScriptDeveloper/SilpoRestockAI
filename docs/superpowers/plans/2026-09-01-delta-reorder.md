# Delta order builder — plan

Design: [`../specs/2026-09-01-delta-reorder-design.md`](../specs/2026-09-01-delta-reorder-design.md).
Notion task 14. No schema change.

## Step 1 — models

- `model/ReplacementOption(String productId, String name, BigDecimal price)`.
- `model/ReplacementSuggestion(String requestedName, List<ReplacementOption> options)`.
- `model/DeltaOrder(UUID userId, OrderType type, String triggerItem, CartSummary cart,
  List<String> reordered, List<ReplacementSuggestion> pendingReplacements, BigDecimal estimatedSavings,
  List<String> excluded)` with `isEmpty()`.

## Step 2 — response keys

`utils/McpResponses` gains `REPLACEMENTS`, `PROMOTIONS` and `OLD_PRICE`, next to the rest.

## Step 3 — a shared helper on `CartBuildingService`

`unresolvedNames(List<ShoppingListItem>, List<ResolvedProduct>)` extracted from `buildCart` and made
public, so the reorder asks the same question the same way rather than repeating the stream.

## Step 4 — `service/ReorderService`

- `buildScheduledDeltaOrder(userId)` → `SCHEDULED_REORDER`; `buildTriggeredDeltaOrder(userId, item)`
  → `AD_HOC` with the item folded into the needs; both call one private `build`.
- Needs = `getUpcomingNeeds` minus `getRemovalCandidates`. Empty → empty `DeltaOrder`, no MCP calls.
- Quantities from the current baseline, defaulting to one unit.
- `promotions(userId, branchId)` → map of normalised name → promoted product id, price, old price.
- Resolve, swap in promo variants, accumulate savings, add, verify with a null slot.
- Missing = needed names not in the verified cart; `replacements(...)` per missing name, capped at
  `MAX_REPLACEMENT_LOOKUPS`.

## Step 5 — the stub records arguments

`StubMcpServer` keeps the arguments of each `tools/call` so a test can assert the delta carried only
the needed items: `callArguments(String tool)`.

## Step 6 — `ReorderIntegrationTest`

1. only the needed items are searched for — a delta, not a replan;
2. removal candidates never reach the search call;
3. an item the cart did not end up containing produces replacement suggestions, and the order still
   builds;
4. a promoted item is added by its promo product id and the savings estimate is the price difference;
5. scheduled and triggered entry points produce the same shape, differing only in type and trigger;
6. a triggered item that was not in the needs list is still ordered;
7. nothing needed → an empty `DeltaOrder` and no MCP calls at all;
8. quantities come from the baseline.

## Step 7 — close out

`make format`, `./gradlew test`, `./gradlew build`, commit, tick Notion, ff-merge into `main`.
