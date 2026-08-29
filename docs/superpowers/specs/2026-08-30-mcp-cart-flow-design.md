# MCP cart session flow

Notion task `09. MCP cart session flow (cart → time slots → products → add)` (Phase `4. First Order`, Must
have, size L, depends on `02`, `08` — both done).

## Context

The hackathon's own requirement is evidence that this is an agent: a real sequence of MCP tool calls against
`mcp.silpo.ua`, not a scripted mock. Product brief user flow #1, steps 4–5. Everything before this task
produced data; this is the first task that changes something on Silpo's side.

The documented sequence, which this service follows exactly:

```
1. silpo_get_my_shopping_cart          → cartId
2. silpo_get_shopping_cart_by_id       → branchId, deliveryType, timeslot
3. silpo_get_time_slots                ← mandatory slot validation
4. silpo_find_products_batch(items[])  → productId + companyId + branchId
5. silpo_add_or_update_cart_products   → add everything
6. silpo_get_shopping_cart_by_id       ← verify
```

## The problem this design is mostly about

No real Silpo guest account exists in this repository, so the exact JSON these six tools answer with has
never been observed. Task 06 hit the same wall for the profile tools and solved it by letting Claude
normalise the output. That answer is wrong here for three reasons: this is the demo hot path and six model
calls would make it slow, the values needed are identifiers rather than prose, and a model that rewrites a
digit in a `productId` fails in a way nobody notices until the basket is wrong.

### Lenient key extraction instead

`utils/McpResponses` walks the response tree — `structuredContent` when the server sends one, otherwise the
text block parsed as JSON — and pulls the first value under a known key name, at any depth. The key names
live in one constant block. When the first live call disagrees with an assumption, the fix is one line in
one file, in front of a jury if it comes to that.

Hard-coded DTOs matching the documentation's examples would be clearer to read and would fail as silent
`null`s the moment the real server nests something differently.

## Decisions

### One method per documented step

`getOrCreateCartContext`, `validateTimeSlot`, `resolveProducts`, `addProductsToCart`, `getVerifiedCart` —
each public, each independently callable. The task asks for this and the reason is the demo: a step that is
its own method is its own log line and its own failure point, and `buildCart` is then visibly the six steps
in order rather than one long method nobody can read on a projector.

### The INFO log is a deliverable, not debugging

Each step logs `MCP → tool(key args)` before the call and `MCP ← what came back` after it. The Silpo client
logs at DEBUG, which is the right level for a library and the wrong level for evidence. A test asserts the
tool names appear at INFO, because "the log is demo-recordable" is an acceptance criterion and an unasserted
criterion is a wish.

### Unresolved items degrade, missing time slots do not

`silpo_find_products_batch` not matching an item is normal — Silpo does not stock everything a recipe names.
Those items are collected into `CartSummary.unresolved` and the cart is built from what did resolve, so #10
can say "не знайшов: X, Y" instead of showing nothing.

No valid time slot is different: products added to a cart that cannot be delivered are worse than an error,
because the failure surfaces at checkout instead of here. That raises `CartBuildException`.

### Loyalty bonuses are surfaced, never applied

When `bonusAvailable > 0`, `bonusRequested == null` and `isEnabled`, the summary carries
`bonusDecisionPending = true` and the bonus amount. Spending someone's loyalty points without asking is not
a default anyone should choose for them, and #10 owns the confirmation UX.

### The existing cart is left alone

The service adds and updates its own lines. It never clears the cart: a guest may have put something there
themselves, and a demo that silently deletes a real person's basket is a worse story than a duplicated line.

### Nothing is persisted here

No `customer_order` row, no baseline. The task scopes both to #10, and writing a DRAFT order that #10 would
have to reconcile with its own would be inventing that task's design for it.

## Design

```
service/ShoppingListService (task 08)
        │  List<ShoppingListItem>
        ▼
service/CartBuildingService.buildCart(userId, items)
        ├── 1,2  getOrCreateCartContext  → CartContext(cartId, branchId, companyId, deliveryType, timeslot)
        ├── 3    validateTimeSlot        → throws CartBuildException when the server offers none
        ├── 4    resolveProducts         → List<ResolvedProduct> + unresolved names, chunked at 30
        ├── 5    addProductsToCart       → one call with everything that resolved
        └── 6    getVerifiedCart         → CartSummary(items, total, validations, loyalty, checkout links)
                    │
                    └── utils/McpResponses    lenient key extraction over structuredContent or text
```

### Types

| Record | Fields |
|---|---|
| `CartContext` | `cartId`, `branchId`, `companyId`, `deliveryType`, `timeslot` |
| `ResolvedProduct` | `requestedName`, `productId`, `companyId`, `branchId`, `quantity`, `unit` |
| `CartSummary` | `cartId`, `items` (`List<BasketItem>`), `total`, `validations`, `bonusAvailable`, `bonusDecisionPending`, `checkoutWebLink`, `checkoutMobileLink`, `unresolved` |

`BasketItem` already exists from task 05 and is what `customer_order` and `baseline_basket` store, so the
summary speaks in the type the rest of the system already uses.

### Chunking

`silpo_find_products_batch` documents a 30-item limit. `resolveProducts` splits anything larger and issues
one call per chunk; 45 items is two calls, and the test says so.

### New files

| File | Holds |
|---|---|
| `service/CartBuildingService` | the six steps and `buildCart` |
| `model/CartContext`, `ResolvedProduct`, `CartSummary` | the types above |
| `exception/CartBuildException` | 502, raised when the cart cannot be built at all |
| `utils/McpResponses` | lenient extraction, and the one block of key names |

### Modified

`support/StubMcpServer` gains per-tool scripted responses — it currently answers every `tools/call` with one
canned string, which cannot express a six-step conversation.

## Testing

`CartBuildingIntegrationTest`, against the stub MCP server over real HTTP, with a token row inserted so the
client believes the user is connected.

| Test | Asserts |
|---|---|
| the whole sequence | all six tools called, in the documented order, and a populated `CartSummary` |
| chunking | 45 items produce exactly 2 `silpo_find_products_batch` calls |
| partial resolution | unmatched names come back in `unresolved`; the rest still reach the cart |
| loyalty | `bonusAvailable` surfaced, `bonusDecisionPending` true, no bonus argument sent |
| no time slots | `CartBuildException`, and no `silpo_add_or_update_cart_products` call |
| the demo log | every tool name appears at INFO (logback `ListAppender`) |

The acceptance criterion "a manual smoke test against the real MCP server" cannot run in CI — it needs a
real OAuth'd guest account. README gets a runbook for it, and the Notion checkbox stays unticked until
someone runs it for real.

## Out of scope

Telegram, confirmation, and persisting the order are #10. Promotions, replacements and cheaper-analogue
search are #13 and later. This task ends at a verified cart and a summary object.
